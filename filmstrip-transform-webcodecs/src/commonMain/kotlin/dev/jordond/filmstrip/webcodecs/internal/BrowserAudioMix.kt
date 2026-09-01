@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.ResolvedClip
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

/**
 * Mixes every track's audio into buffers covering the composition timeline, a window at a time.
 *
 * Each clip is decoded to its own buffer, then played through a gain node into an
 * [OfflineAudioContext], which resamples every source to the context's own rate, sums whatever
 * overlaps, and loops a clip on a track whose [ResolvedTrack.looping] asks for it. Nothing here
 * sums or resamples a single sample by hand.
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
   * A looping clip is decoded whole and kept for the run, since any window can reach any part of
   * it. Its cost is the clip's own length, not the timeline's.
   *
   * @param window How much of the timeline one pass covers. A test drives this smaller so a short
   *   fixture still crosses boundaries.
   */
  suspend fun mixInto(
    tracks: List<ResolvedTrack>,
    format: AudioFormat,
    duration: Duration,
    sources: SourceCache,
    window: Duration = WINDOW,
    sink: suspend (AudioBuffer) -> Unit,
  ) {
    val peak = peakBytes(tracks, format, duration, window)
    if (peak > MAX_MIX_BYTES) throw BrowserExportFailure(tooLarge(peak))

    val placed = placed(tracks)
    val rate = format.sampleRate
    val total = frames(duration, rate)
    val windowFrames = frames(window, rate)
    val pad = frames(PAD, rate)
    val looped = mutableMapOf<Int, AudioBuffer?>()

    var start = 0
    while (start < total) {
      currentCoroutineContext().ensureActive()

      val length = minOf(windowFrames, total - start)
      val lead = minOf(pad, start)
      val context = OfflineAudioContext(format.channelCount, lead + length, rate.toFloat())
      val from = (start - lead).toDouble() / rate
      val to = (start + length).toDouble() / rate

      placed.forEachIndexed { index, clip -> place(clip, index, context, from, to, sources, looped) }

      val rendered = context.startRendering().await()
      sink(if (lead == 0) rendered else withoutLead(context, rendered, lead, length))
      start += length
    }
  }

  /**
   * Schedules whatever of one clip falls inside the context covering `[contextStart, contextEnd)`,
   * both in output seconds. A clip with no audio track, or a gain of zero, contributes nothing and
   * is skipped.
   */
  private suspend fun place(
    placed: Placed,
    index: Int,
    context: OfflineAudioContext,
    contextStart: Double,
    contextEnd: Double,
    sources: SourceCache,
    looped: MutableMap<Int, AudioBuffer?>,
  ) {
    val clip = placed.clip
    if (clip.gain <= 0f) return

    val offset = placed.offset.seconds()
    val period = clip.duration.seconds()
    if (period <= 0.0 || contextEnd <= offset) return

    val audioTrack = sources.open(clip.source)?.audioTrack() ?: return
    val start = maxOf(offset, contextStart)

    if (placed.looping) {
      if (index !in looped) {
        looped[index] = decode(audioTrack, clip.start.seconds(), clip.end.seconds(), context)
      }
      val buffer = looped[index] ?: return
      schedule(context, buffer, clip.gain, start - contextStart, looping = true, from = (start - offset) % period)
      return
    }

    val end = minOf(offset + period, contextEnd)
    if (end <= start) return
    val trimStart = clip.start.seconds()
    val buffer = decode(audioTrack, trimStart + (start - offset), trimStart + (end - offset), context) ?: return
    schedule(context, buffer, clip.gain, start - contextStart, looping = false)
  }

  /**
   * Wires one decoded buffer into [context]: a gain node set to [gain], started at [offsetSeconds]
   * on the context's timeline and playing from [from] seconds into the buffer.
   *
   * @param into Where the gain node feeds, which a live preview points at its own master gain so
   *   monitor volume reaches every clip at once.
   * @return The source node, for a caller that has to stop it again.
   */
  internal fun schedule(
    context: BaseAudioContext,
    buffer: AudioBuffer,
    gain: Float,
    offsetSeconds: Double,
    looping: Boolean,
    from: Double = 0.0,
    into: AudioNode = context.destination,
  ): AudioBufferSourceNode {
    val gainNode = context.createGain()
    gainNode.gain.value = gain
    gainNode.connect(into)

    val source = context.createBufferSource()
    source.buffer = buffer
    source.loop = looping
    source.connect(gainNode)
    source.start(offsetSeconds, from)
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
   * One window of output plus the slice of every clip that a window reaches, which is what a clip
   * runs to when it is shorter than a window. A looping clip counts whole, since it is decoded once
   * and held. Nothing here grows with [duration] past the first window, which is the point of
   * rendering in windows at all.
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
    val clips =
      placed(tracks)
        .filter { it.clip.gain > 0f }
        .sumOf { placed ->
          val audio = placed.clip.info.audio ?: return@sumOf 0L
          val span = if (placed.looping) placed.clip.duration else minOf(placed.clip.duration, reach)
          bytesOf(span, audio.sampleRate, audio.channelCount)
        }
    return bytesOf(minOf(window, duration), format.sampleRate, format.channelCount) + clips
  }

  /**
   * Why a mix needing [peak] bytes was refused, worded for a caller to show.
   */
  fun tooLarge(peak: Long): String =
    "This export's audio needs ${megabytes(peak)} to mix, past the ${megabytes(MAX_MIX_BYTES)} the " +
      "browser mixer holds at once. Fewer overlapping audio tracks, or shorter looping clips, both " +
      "bring it down."

  /**
   * How many frames of [sampleRate] audio [duration] covers, never fewer than one so a timeline
   * shorter than a single frame still renders something.
   */
  private fun frames(
    duration: Duration,
    sampleRate: Int,
  ): Int = (duration.seconds() * sampleRate).roundToInt().coerceAtLeast(1)

  internal fun placed(tracks: List<ResolvedTrack>): List<Placed> =
    tracks.flatMap { track ->
      var offset = track.start
      track.clips.map { clip -> Placed(clip, offset, track.looping).also { offset += clip.duration } }
    }

  private fun bytesOf(
    duration: Duration,
    sampleRate: Int,
    channelCount: Int,
  ): Long = (duration.seconds() * sampleRate).toLong() * channelCount * BYTES_PER_SAMPLE

  private fun megabytes(bytes: Long): String = "${bytes / BYTES_PER_MEGABYTE} MB"

  internal fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

  /**
   * One clip with the place on the output timeline its track puts it at.
   */
  internal class Placed(
    val clip: ResolvedClip,
    val offset: Duration,
    val looping: Boolean,
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
}
