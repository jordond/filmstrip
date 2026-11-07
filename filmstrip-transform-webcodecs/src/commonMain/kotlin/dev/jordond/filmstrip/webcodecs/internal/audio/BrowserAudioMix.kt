@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.audio

import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.PulledStream
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix.PAD
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBufferSink
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBufferSourceNode
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioNode
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioParam
import dev.jordond.filmstrip.webcodecs.internal.interop.BaseAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.Float32Array
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.OfflineAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.WrappedAudioBuffer
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
 * clip: the plan already holds one clip per pass a looping track lays. A clip at another speed goes
 * through a [ClipStretch] first, so what reaches the gain node is already output time.
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
   * @param window How much of the timeline one pass covers.
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
    val stretches = mutableMapOf<Placed, ClipStretch>()

    try {
      var start = 0
      while (start < total) {
        currentCoroutineContext().ensureActive()

        val length = minOf(windowFrames, total - start)
        val lead = minOf(pad, start)
        val context = OfflineAudioContext(format.channelCount, lead + length, rate.toFloat())
        val span =
          MixSpan(
            context = context,
            from = (start - lead).toDouble() / rate,
            audibleFrom = start.toDouble() / rate,
            to = (start + length).toDouble() / rate,
          )
        decoded.clear()

        placed.forEach { clip -> place(clip, span, sources, decoded, stretches) }

        val rendered = context.startRendering().await()
        sink(if (lead == 0) rendered else withoutLead(context, rendered, lead, length))
        start += length
      }
    } finally {
      stretches.values.forEach { it.close() }
    }
  }

  /**
   * Schedules whatever of one clip falls inside [span]. A clip with no audio track, or a curve that
   * is silent everywhere, contributes nothing and is skipped.
   *
   * A pass the window holds whole asks for the clip's own trim, spelled from the trim itself so every
   * pass of one clip asks for the identical stretch and the run of them costs one decode. Measuring
   * the end back from the pass's own offset instead lands an ulp apart on each of them, which is a
   * decode each.
   */
  private suspend fun place(
    placed: Placed,
    span: MixSpan,
    sources: SourceCache,
    decoded: DecodeCache,
    stretches: MutableMap<Placed, ClipStretch>,
  ) {
    val clip = placed.clip
    if (clip.gain.peak <= 0f) return

    val offset = placed.offset.seconds()
    val period = clip.outputDuration.seconds()
    if (period <= 0.0 || span.to <= offset) return

    val audioTrack = sources.open(clip.source)?.audioTrack() ?: return
    val start = maxOf(offset, span.from)
    val end = minOf(offset + period, span.to)
    if (end <= start) return

    val whole = offset >= span.from && offset + period <= span.to
    val slice =
      if (whole) {
        DecodeCache.Slice(clip.source, clip.start.seconds(), clip.end.seconds(), clip.speed)
      } else {
        DecodeCache.Slice(
          clip.source,
          clip.sourceTimeAt(start.asDuration()).seconds(),
          clip.sourceTimeAt(end.asDuration()).seconds(),
          clip.speed,
        )
      }
    val buffer =
      if (clip.speed == 1.0) {
        decoded.of(slice) { decode(audioTrack, slice.fromSeconds, slice.toSeconds, span.context) }
      } else {
        stretched(placed, audioTrack, span, start, end, slice, whole, decoded, stretches)
      }
    if (buffer == null) return
    val curve = curveFor(clip.gain, start - offset, end - start)
    schedule(span.context, buffer, curve, start - span.from)
  }

  /**
   * The buffer one pass at another speed plays over `[start, end)`, in output seconds.
   *
   * A whole pass stretches fresh from the head of its trim, which gives the same samples every time,
   * so a run of them shares the decode cache the way any other whole pass does. One a window cuts
   * carries its stretcher across the boundary in [stretches], so the two sides stay in phase, and it
   * holds on to [PAD] of output for the lead the next window renders. A pass that ended inside that
   * lead has nothing left in the kept output, and is left out.
   */
  private suspend fun stretched(
    placed: Placed,
    audioTrack: InputAudioTrack,
    span: MixSpan,
    start: Double,
    end: Double,
    slice: DecodeCache.Slice,
    whole: Boolean,
    decoded: DecodeCache,
    stretches: MutableMap<Placed, ClipStretch>,
  ): AudioBuffer? {
    if (end <= span.audibleFrom) return null
    val clip = placed.clip
    if (whole) {
      stretches.remove(placed)?.close()
      return decoded.of(slice) {
        val stretch = ClipStretch.of(clip, placed.offset, audioTrack)
        try {
          stretch.between(placed.offset, placed.offset + clip.outputDuration)?.toBuffer(span.context)
        } finally {
          stretch.close()
        }
      }
    }
    val stretch =
      stretches[placed] ?: ClipStretch.of(clip, placed.offset, audioTrack, PAD).also { stretches[placed] = it }
    val audio = stretch.between(start.asDuration(), end.asDuration())
    if (end >= placed.offset.seconds() + clip.outputDuration.seconds()) stretches.remove(placed)?.close()
    return audio?.toBuffer(span.context)
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
   *
   * @param open Starts the sink decoding the span.
   */
  internal suspend fun decode(
    audioTrack: InputAudioTrack,
    startSeconds: Double,
    endSeconds: Double,
    context: BaseAudioContext,
    open: () -> MediabunnyIterator<WrappedAudioBuffer> = {
      AudioBufferSink(audioTrack).buffers(startSeconds, endSeconds)
    },
  ): AudioBuffer? {
    val sampleRate = audioTrack.getSampleRate().await().toDouble()
    val channels = audioTrack.getNumberOfChannels().await().toDouble()
    val channelCount = channels.toInt()
    val frames = bufferFrames(audioFramesOf(endSeconds - startSeconds, sampleRate.toInt()))
    val buffer = context.createBuffer(channelCount, frames, sampleRate.toFloat())

    // The sink closes each sample as it wraps it, so a wrapped buffer has nothing left to release.
    val stream = PulledStream(open()) {}
    var decoded = false
    try {
      while (true) {
        val wrapped = stream.next() ?: break
        decoded = true
        val startInChannel = ((wrapped.timestamp - startSeconds) * sampleRate).roundToInt().coerceIn(0, frames - 1)
        for (channel in 0 until channelCount) {
          buffer.copyToChannel(wrapped.buffer.getChannelData(channel), channel, startInChannel)
        }
      }
    } finally {
      stream.close()
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
   * and costs nothing. A clip at another speed costs the output it plays over the window, since the
   * source it reads runs through its stretcher and is not held.
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
  ): Int = bufferFrames(audioFramesOf(duration, sampleRate))

  private fun bufferFrames(frames: Long): Int = frames.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

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
    val length = minOf(outputLengthOf(clip.sourceDuration, clip.speed), reach)
    return bytesOf(length, audio.sampleRate, audio.channelCount)
  }

  // The clips a window holds one buffer each for, which is the cache's own whole-pass key.
  private fun List<ResolvedClip>.distinctByTrim(): List<ResolvedClip> =
    distinctBy { DecodeCache.Slice(it.source, it.start.seconds(), it.end.seconds(), it.speed) }

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
   * The context one window renders into and where it sits on the timeline, in output seconds.
   *
   * @property from Where the context opens, lead included.
   * @property audibleFrom Where the window's own output opens, past the lead that is dropped again.
   * @property to Where the context closes.
   */
  private class MixSpan(
    val context: OfflineAudioContext,
    val from: Double,
    val audibleFrom: Double,
    val to: Double,
  )

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
