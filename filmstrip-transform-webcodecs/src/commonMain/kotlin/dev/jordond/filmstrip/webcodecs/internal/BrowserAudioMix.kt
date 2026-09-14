@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.BrowserAudioMix.PAD
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Mixes every track's audio into buffers covering the composition timeline, a window at a time.
 *
 * Each clip is decoded to its own buffer, then played through a gain node into an
 * [OfflineAudioContext], which resamples every source to the context's own rate and sums whatever
 * overlaps. Nothing here sums or resamples a single sample by hand, and nothing here repeats a
 * clip: the plan already holds one clip per pass a looping track lays.
 */
internal object BrowserAudioMix {
  /**
   * Renders [tracks] into [format], handing [sink] one [AudioBuffer] at a time until [duration] of
   * output is covered.
   *
   * What is resident is one window of output and the slice of each clip that window reaches,
   * whatever the timeline runs to. Every window is rendered with [PAD] of lead in front of it that
   * is dropped again: a clip carried across a boundary starts a fresh resampler that has none of
   * its own history, and the transient that costs lands in the lead instead of in the output.
   *
   * Decoded slices are cached on what was asked for, so a looping track's repeated passes of one
   * clip decode once. Only a pass a window boundary cuts asks for a slice of its own.
   *
   * @param window How much of the timeline one pass covers. A test drives this smaller so a short
   *   fixture still crosses boundaries.
   * @param decoded Where decoded slices are held, the way [sources] holds readers. Emptied at every
   *   window boundary, since a buffer belongs to the context that made it.
   */
  suspend fun mixInto(
    tracks: List<ResolvedTrack>,
    format: AudioFormat,
    duration: Duration,
    sources: SourceCache,
    window: Duration = WINDOW,
    decoded: DecodeCache = DecodeCache(),
    sink: suspend (AudioBuffer) -> Unit,
  ) {
    val peak = peakBytes(tracks, format, duration, window)
    if (peak > MAX_MIX_BYTES) throw BrowserExportFailure(tooLarge(peak))

    val placed = placed(tracks)
    val rate = format.sampleRate
    val total = frames(duration, rate)
    val windowFrames = frames(window, rate)
    val pad = frames(PAD, rate)

    var start = 0
    while (start < total) {
      currentCoroutineContext().ensureActive()

      val length = minOf(windowFrames, total - start)
      val lead = minOf(pad, start)
      val context = OfflineAudioContext(format.channelCount, lead + length, rate.toFloat())
      val from = (start - lead).toDouble() / rate
      val to = (start + length).toDouble() / rate
      decoded.clear()

      placed.forEach { clip -> place(clip, context, from, to, sources, decoded) }

      val rendered = context.startRendering().await()
      sink(if (lead == 0) rendered else withoutLead(context, rendered, lead, length))
      start += length
    }
  }

  /**
   * Schedules whatever of one clip falls inside the context covering `[contextStart, contextEnd)`,
   * both in output seconds. A clip with no audio track, or a curve that is silent everywhere,
   * contributes nothing and is skipped.
   */
  private suspend fun place(
    placed: Placed,
    context: OfflineAudioContext,
    contextStart: Double,
    contextEnd: Double,
    sources: SourceCache,
    decoded: DecodeCache,
  ) {
    val clip = placed.clip
    if (clip.gain.peak <= 0f) return

    val offset = placed.offset.seconds()
    val period = clip.duration.seconds()
    if (period <= 0.0 || contextEnd <= offset) return

    val audioTrack = sources.open(clip.source)?.audioTrack() ?: return
    val start = maxOf(offset, contextStart)
    val end = minOf(offset + period, contextEnd)
    if (end <= start) return

    // A pass the window holds whole asks for the clip's own trim, spelled from the trim itself so
    // every pass of one clip asks for the identical stretch and the run of them costs one decode.
    // Measuring the end back from the pass's own offset instead lands an ulp apart on each of them,
    // which is a decode each.
    val trimStart = clip.start.seconds()
    val slice =
      if (offset >= contextStart && offset + period <= contextEnd) {
        DecodeCache.Slice(clip.source, trimStart, clip.end.seconds())
      } else {
        DecodeCache.Slice(clip.source, trimStart + (start - offset), trimStart + (end - offset))
      }
    val buffer = decoded.of(slice) { decode(audioTrack, slice.fromSeconds, slice.toSeconds, context) } ?: return
    val curve = curveFor(clip.gain, start - offset, end - start)
    schedule(context, buffer, curve, start - contextStart)
  }

  /**
   * The stretch of [gain] one scheduled node is automated from, rebased so the curve's zero sits
   * where that node starts.
   *
   * [elapsed] is how far into the clip the node opens and [length] how long it sounds, both in
   * seconds. A constant comes back untouched, since a node scaling by one number needs no
   * breakpoints at all.
   */
  private fun curveFor(
    gain: ResolvedGain,
    elapsed: Double,
    length: Double,
  ): ResolvedGain {
    if (gain.isConstant) return gain
    return gain.window(elapsed.asDuration(), length.asDuration())
  }

  /**
   * Writes [curve] onto [param] as automation, with the curve's own zero landing at [atSeconds] on
   * the context's clock.
   *
   * The first event pins the gain the curve already holds where it opens, which is what carries on
   * a ramp the previous window left half way through rather than starting it over. A segment with
   * no length is a step, so it is pinned rather than ramped to.
   */
  private fun automate(
    param: AudioParam,
    curve: ResolvedGain,
    atSeconds: Double,
  ) {
    param.setValueAtTime(curve.gainAt(Duration.ZERO), atSeconds)
    curve.segments.forEach { segment ->
      val at = atSeconds + segment.end.seconds()
      if (segment.start == segment.end) {
        param.setValueAtTime(segment.endGain, at)
      } else {
        param.linearRampToValueAtTime(segment.endGain, at)
      }
    }
  }

  /**
   * Wires one decoded buffer into [context]: a gain node carrying [gain], started at
   * [offsetSeconds] on the context's timeline and playing the buffer from its own start.
   *
   * [gain] is read in the node's own time, so a curve handed here has already been rebased to where
   * the node starts. A constant is one write on the parameter and a ramping curve is scheduled as
   * automation against the context's clock.
   *
   * @param into Where the gain node feeds, which a live preview points at its own master gain so
   *   monitor volume reaches every clip at once.
   * @return The source node, for a caller that has to stop it again.
   */
  internal fun schedule(
    context: BaseAudioContext,
    buffer: AudioBuffer,
    gain: ResolvedGain,
    offsetSeconds: Double,
    into: AudioNode = context.destination,
  ): AudioBufferSourceNode {
    val gainNode = context.createGain()
    val constant = gain.constant
    if (constant != null) {
      gainNode.gain.value = constant
    } else {
      automate(gainNode.gain, gain, offsetSeconds)
    }
    gainNode.connect(into)

    val source = context.createBufferSource()
    source.buffer = buffer
    source.connect(gainNode)
    source.start(offsetSeconds)
    return source
  }

  /**
   * The window's own frames, with the lead that was rendered only to settle the resamplers dropped.
   */
  private fun withoutLead(
    context: OfflineAudioContext,
    rendered: AudioBuffer,
    lead: Int,
    length: Int,
  ): AudioBuffer {
    val trimmed = context.createBuffer(rendered.numberOfChannels, length, rendered.sampleRate)
    for (channel in 0 until rendered.numberOfChannels) {
      val samples = Float32Array(length)
      rendered.copyFromChannel(samples, channel, lead)
      trimmed.copyToChannel(samples, channel, 0)
    }
    return trimmed
  }

  /**
   * Decodes `[startSeconds, endSeconds)` into one buffer at the source's own sample rate, copying
   * in whatever chunks mediabunny's sink yields for that span. Null when the span carries no audio.
   *
   * A chunk can open before [startSeconds], since the sink yields whole decoded blocks rather than
   * cutting one to the span it was asked for. Such a chunk is pinned to the head of the buffer,
   * which is why a window renders a lead it then drops.
   */
  internal suspend fun decode(
    audioTrack: InputAudioTrack,
    startSeconds: Double,
    endSeconds: Double,
    context: BaseAudioContext,
  ): AudioBuffer? {
    val sampleRate = audioTrack.getSampleRate().await().toDouble()
    val channels = audioTrack.getNumberOfChannels().await().toDouble()
    val channelCount = channels.toInt()
    val frames = ((endSeconds - startSeconds) * sampleRate).roundToInt().coerceAtLeast(1)
    val buffer = context.createBuffer(channelCount, frames, sampleRate.toFloat())

    val iterator = AudioBufferSink(audioTrack).buffers(startSeconds, endSeconds)
    var step = iterator.next().await()
    var decoded = false
    while (!step.done) {
      val wrapped = step.value
      if (wrapped != null) {
        decoded = true
        val startInChannel = ((wrapped.timestamp - startSeconds) * sampleRate).roundToInt().coerceIn(0, frames - 1)
        for (channel in 0 until channelCount) {
          buffer.copyToChannel(wrapped.buffer.getChannelData(channel), channel, startInChannel)
        }
      }
      step = iterator.next().await()
    }
    return if (decoded) buffer else null
  }

  /**
   * What rendering [tracks] into [format] over [duration] costs at its peak.
   *
   * One window of output plus what the decode cache holds over that window, counted over the
   * cache's own key. Every pass a window holds whole asks for its clip's own trim, so a looping
   * track's run of passes shares one buffer and so do two tracks reading the same trim. A window
   * has two edges and the pass each one cuts asks for a stretch of its own, which costs a track one
   * more buffer per edge, and only a track laying more passes than it has clips has any to pay:
   * a track whose passes are all different clips is already counted a buffer each. Nothing here
   * grows with [duration] past the first window, which is the point of rendering in windows at all.
   *
   * A clip decodes at its own rate and channel count rather than [format]'s, so a 96kHz 5.1 source
   * costs six times what a 48kHz stereo one does over the same span. A silent clip is never decoded
   * and costs nothing.
   */
  fun peakBytes(
    tracks: List<ResolvedTrack>,
    format: AudioFormat,
    duration: Duration,
    window: Duration = WINDOW,
  ): Long {
    val reach = minOf(window + PAD, duration)
    val sounding = tracks.map { track -> track.clips.filter { it.gain.peak > 0f } }
    val shared = sounding.flatten().distinctByTrim().sumOf { bytesOf(it, reach) }
    val cutByAnEdge =
      sounding.sumOf { clips ->
        val distinct = clips.distinctByTrim().size
        val extra = minOf(clips.size, distinct + WINDOW_EDGES) - distinct
        (clips.maxOfOrNull { bytesOf(it, reach) } ?: 0L) * extra
      }
    return bytesOf(minOf(window, duration), format.sampleRate, format.channelCount) + shared + cutByAnEdge
  }

  /**
   * Why a mix needing [peak] bytes was refused, worded for a caller to show.
   */
  fun tooLarge(peak: Long): String =
    "This export's audio needs ${megabytes(peak)} to mix, past the ${megabytes(MAX_MIX_BYTES)} the " +
      "browser mixer holds at once. Fewer overlapping audio tracks, or shorter clips on them, both " +
      "bring it down."

  /**
   * How many frames of [sampleRate] audio [duration] covers, never fewer than one so a timeline
   * shorter than a single frame still renders something.
   */
  private fun frames(
    duration: Duration,
    sampleRate: Int,
  ): Int = (duration.seconds() * sampleRate).roundToInt().coerceAtLeast(1)

  internal fun placed(tracks: List<ResolvedTrack>): List<Placed> = tracks.flatMap { it.clips }.map(::Placed)

  private fun bytesOf(
    duration: Duration,
    sampleRate: Int,
    channelCount: Int,
  ): Long = (duration.seconds() * sampleRate).toLong() * channelCount * BYTES_PER_SAMPLE

  // What one buffer of this clip costs, which is however much of it a window reaches.
  private fun bytesOf(
    clip: ResolvedClip,
    reach: Duration,
  ): Long {
    val audio = clip.info.audio ?: return 0L
    return bytesOf(minOf(clip.duration, reach), audio.sampleRate, audio.channelCount)
  }

  // The clips a window holds one buffer each for, which is the cache's own whole-pass key.
  private fun List<ResolvedClip>.distinctByTrim(): List<ResolvedClip> =
    distinctBy { Triple(it.source, it.start, it.end) }

  private fun megabytes(bytes: Long): String = "${bytes / BYTES_PER_MEGABYTE} MB"

  internal fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

  private fun Double.asDuration(): Duration = toDuration(DurationUnit.SECONDS)

  /**
   * One clip with the place on the output timeline the plan laid it at.
   */
  internal class Placed(
    val clip: ResolvedClip,
  ) {
    val offset: Duration get() = clip.span.start
  }

  /**
   * The most memory one render is allowed to allocate. A window at a time keeps a real timeline far
   * under this, so what trips it is a composition wide enough that one window of it does not fit.
   */
  const val MAX_MIX_BYTES: Long = 512L * 1024 * 1024

  /**
   * How much of the timeline one pass renders.
   */
  val WINDOW = 30.seconds

  /**
   * How much extra is rendered in front of a window and then dropped, long enough to cover both a
   * resampler settling and a decoded chunk that opens before the window does.
   */
  val PAD = 100.milliseconds

  // Float32, which is what an AudioBuffer stores whatever the source was encoded as.
  private const val BYTES_PER_SAMPLE = 4L
  private const val BYTES_PER_MEGABYTE = 1024L * 1024

  // A window opens at one edge and closes at another, and each can cut a pass in half.
  private const val WINDOW_EDGES = 2
}

/**
 * The buffers the passes of one window were decoded into, keyed on the stretch of source each asked
 * for.
 *
 * A looping track lays the same clip down over and over, and every pass a window holds whole asks
 * for that clip's own trim, so a run of them costs one decode between them. A buffer belongs to the
 * context that made it, so [clear] drops the lot at each window boundary. [decodes] keeps counting
 * across them, which is what says a repeated pass added none.
 */
internal class DecodeCache {
  private val buffers = mutableMapOf<Slice, AudioBuffer?>()

  var decodes: Int = 0
    private set

  /**
   * The buffer [slice] decodes to, running [decode] only the first time this window is asked for
   * it. A slice that carries no audio is remembered as nothing rather than decoded again.
   */
  suspend fun of(
    slice: Slice,
    decode: suspend () -> AudioBuffer?,
  ): AudioBuffer? {
    if (slice !in buffers) {
      decodes++
      buffers[slice] = decode()
    }
    return buffers[slice]
  }

  fun clear(): Unit = buffers.clear()

  /**
   * One stretch of one source, in the source's own seconds, as a decode was asked for it.
   */
  internal data class Slice(
    val source: MediaSource,
    val fromSeconds: Double,
    val toSeconds: Double,
  )
}
