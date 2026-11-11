@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.audio

import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.runsAs
import dev.jordond.filmstrip.webcodecs.internal.PulledStream
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBufferSink
import dev.jordond.filmstrip.webcodecs.internal.interop.BaseAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.WrappedAudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * One clip's source audio decoded, played through a [TimeStretch] and then through the clip's audio effects, asked for
 * a stretch of the timeline at a time.
 *
 * The effects run in one [KernelChain] over the frames in the order they play, each frame once, so a kernel's state
 * carries from one call to the next and the position it is handed is the frame's place on the composition timeline.
 * The chain takes its kernels' latency out, so the clip keeps its length.
 *
 * Source is pulled only when the stretcher needs more, and what no grain reads is moved past uncopied, so a clip costs
 * what its stretcher holds however much source it runs through. Every frame of output is counted from where this
 * opened, and the count stops at [outputLengthOf] the source left, so the length never comes from the stretcher.
 *
 * Not safe for two callers at once. Each call has to carry on from where the last one reached, less [keep].
 */
internal class ClipStretch private constructor(
  clip: ResolvedClip,
  private val opensAt: Duration,
  private val sampleRate: Int,
  private val channelCount: Int,
  keep: Duration,
  private val open: (fromSeconds: Double, toSeconds: Double) -> MediabunnyIterator<WrappedAudioBuffer>,
) {
  private val sourceFrom = clip.sourceTimeAt(opensAt)
  private val sourceFromSeconds = sourceFrom.toDouble(DurationUnit.SECONDS)
  private val sourceToSeconds = clip.end.toDouble(DurationUnit.SECONDS)
  private val totalFrames = frameOf(outputLengthOf(clip.end - sourceFrom, clip.speed))
  private val keepFrames = if (keep > Duration.ZERO) (frameOf(keep) + KEEP_MARGIN_FRAMES).toInt() else 0
  private val stretch = TimeStretch(clip.speed, channelCount, sampleRate, frameOf(clip.end - sourceFrom))

  private var stream: PulledStream<WrappedAudioBuffer>? = null
  private var pending: WrappedAudioBuffer? = null
  private var gapFrames = 0L
  private var fedFrames = 0L
  private var sawAudio = false

  private val opensFrame = timelineFrameOf(opensAt, sampleRate)
  private var adopted: List<ResolvedAudioEffect> = emptyList()
  private var chain: KernelChain? = null
  private var stretchedFrames = 0L

  private var producedFrames = 0L
  private var history = Array(channelCount) { FloatArray(0) }

  /**
   * The frames this clip plays over `[from, to)`, both in composition time, or null when the span lands on none or the
   * source carries no audio there.
   */
  suspend fun between(
    from: Duration,
    to: Duration,
  ): StretchedAudio? {
    val first = frameOf(from - opensAt)
    val last = min(frameOf(to - opensAt), totalFrames)
    if (last <= first) return null

    val fresh = (last - producedFrames).toInt().coerceAtLeast(0)
    val pulled = if (fresh > 0) pullFresh(fresh) else null
    if (!sawAudio) return null

    val heldStart = producedFrames - history[0].size
    check(first in heldStart..producedFrames) {
      "A stretch was asked for frame $first, holding from $heldStart to $producedFrames."
    }
    val frames = (last - first).toInt()
    val out = Array(channelCount) { FloatArray(frames) }
    val fromHistory = (first - heldStart).toInt()
    val heldCount = min(history[0].size - fromHistory, frames)
    for (channel in 0 until channelCount) {
      if (heldCount > 0) history[channel].copyInto(out[channel], 0, fromHistory, fromHistory + heldCount)
      pulled?.get(channel)?.copyInto(out[channel], heldCount)
    }
    if (pulled != null) remember(pulled)
    producedFrames += fresh
    return StretchedAudio(out, frames, sampleRate, opensAt.seconds() + first.toDouble() / sampleRate)
  }

  /**
   * Runs [effects] over every frame not played yet, in place of the effects this was running.
   *
   * The effects it is already running change nothing. Any others start their kernels with no state at the next frame.
   */
  fun adopt(effects: List<ResolvedAudioEffect>) {
    if (effects.runsAs(adopted)) return
    adopted = effects
    val kernels = effects.map { it.effect.newKernel(AudioFormat(sampleRate, channelCount)) }
    val running = chain
    when {
      running != null -> running.swap(kernels)
      kernels.isNotEmpty() -> chain = KernelChain(kernels, channelCount, opensFrame + producedFrames)
    }
  }

  /**
   * Stops decoding. A call to [between] already waiting on a decode finishes against silence.
   */
  fun close() {
    stream?.close()
  }

  // The next frames of output. With effects, source is run through the chain until it has handed that many back, and
  // once the source is over the chain gives up what its kernels still hold.
  private suspend fun pullFresh(frames: Int): Array<FloatArray> {
    val running = chain ?: return pullStretched(frames)
    val out = Array(channelCount) { FloatArray(frames) }
    val block = FloatArray(frames * channelCount)
    var filled = 0
    while (filled < frames) {
      val wanted = frames - filled
      val source = min(wanted.toLong(), totalFrames - stretchedFrames).toInt()
      val written =
        if (source > 0) {
          val pulled = pullStretched(source)
          for (channel in 0 until channelCount) {
            for (index in 0 until source) block[index * channelCount + channel] = pulled[channel][index]
          }
          running.process(block, source)
        } else {
          running.drain(block, wanted)
        }
      if (source <= 0 && written == 0) break
      for (channel in 0 until channelCount) {
        for (index in 0 until written) out[channel][filled + index] = block[index * channelCount + channel]
      }
      filled += written
    }
    return out
  }

  private suspend fun pullStretched(frames: Int): Array<FloatArray> {
    while (!stretch.canPull(frames)) feed()
    stretchedFrames += frames
    return stretch.pull(frames)
  }

  // Moves one step closer to the next grain: a little of a gap the source left, a chunk waiting behind that gap, or
  // the next decoded chunk.
  private suspend fun feed() {
    val waiting = pending
    when {
      gapFrames > 0L -> {
        feedSilence()
      }
      waiting != null -> {
        pending = null
        ingest(waiting, overlapOf(waiting))
      }
      else -> {
        val next = openedStream().next()
        if (next == null) {
          stretch.finish()
        } else {
          sawAudio = true
          gapFrames = max(0L, offsetFromFed(next))
          pending = next
        }
      }
    }
  }

  // A hole in the source's timestamps plays as silence, handed over in small pieces so a file claiming a long hole
  // holds no more than the grains reading it need.
  private fun feedSilence() {
    val frames = min(gapFrames, SILENCE_STEP_FRAMES.toLong()).toInt()
    gapFrames -= frames
    fedFrames += frames
    if (frames <= stretch.skippableFrames) {
      stretch.skip(frames)
    } else {
      stretch.push(Array(channelCount) { FloatArray(frames) }, frames)
    }
  }

  // Lays a chunk in, leaving out the [overlap] frames at its head that were already fed, which is how a chunk that
  // opens before the span was asked for is cut back to it.
  private fun ingest(
    chunk: WrappedAudioBuffer,
    overlap: Long,
  ) {
    val buffer = chunk.buffer
    val skipped = min(overlap, buffer.length.toLong()).toInt()
    val frames = buffer.length - skipped
    if (frames <= 0) return

    fedFrames += frames
    if (frames <= stretch.skippableFrames) {
      stretch.skip(frames)
      return
    }
    val channels =
      Array(channelCount) { channel ->
        val read = FloatArray(frames)
        if (channel < buffer.numberOfChannels) {
          val data = buffer.getChannelData(channel)
          for (index in 0 until frames) read[index] = data.at(skipped + index)
        }
        read
      }
    stretch.push(channels, frames)
  }

  private fun overlapOf(chunk: WrappedAudioBuffer): Long = max(0L, -offsetFromFed(chunk))

  // How far past what was already fed this chunk opens. A rounding of a frame or two either way is the same frame.
  private fun offsetFromFed(chunk: WrappedAudioBuffer): Long {
    val offset = ((chunk.timestamp - sourceFromSeconds) * sampleRate).roundToLong() - fedFrames
    return if (abs(offset) <= TIMESTAMP_SLACK_FRAMES && fedFrames > 0L) 0L else offset
  }

  private fun openedStream(): PulledStream<WrappedAudioBuffer> =
    stream ?: PulledStream(open(sourceFromSeconds, sourceToSeconds)) {}.also { stream = it }

  private fun remember(pulled: Array<FloatArray>) {
    if (keepFrames == 0) return
    val size = pulled[0].size
    history =
      Array(channelCount) { channel ->
        if (size >= keepFrames) {
          pulled[channel].copyOfRange(size - keepFrames, size)
        } else {
          val held = history[channel]
          held.copyOfRange(max(0, held.size + size - keepFrames), held.size) + pulled[channel]
        }
      }
  }

  private fun frameOf(time: Duration): Long = audioFramesOf(time, sampleRate)

  private fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

  companion object {
    /**
     * Opens [clip] for playing from [opensAt] on the composition timeline, at the rate and channel count [audioTrack]
     * decodes to. The source is not touched until the first call to [between].
     *
     * @param keep How much output to hold back for a later call to ask for again.
     * @param open Starts the sink decoding the span, in source seconds.
     */
    suspend fun of(
      clip: ResolvedClip,
      opensAt: Duration,
      audioTrack: InputAudioTrack,
      keep: Duration = Duration.ZERO,
      open: (fromSeconds: Double, toSeconds: Double) -> MediabunnyIterator<WrappedAudioBuffer> = { from, to ->
        AudioBufferSink(audioTrack).buffers(from, to)
      },
    ): ClipStretch =
      ClipStretch(
        clip = clip,
        opensAt = opensAt,
        sampleRate =
          audioTrack
            .getSampleRate()
            .await()
            .toDouble()
            .toInt(),
        channelCount =
          audioTrack
            .getNumberOfChannels()
            .await()
            .toDouble()
            .toInt(),
        keep = keep,
        open = open,
      ).also { it.adopt(clip.audioEffects) }

    private const val KEEP_MARGIN_FRAMES = 2L
    private const val SILENCE_STEP_FRAMES = 4_096
    private const val TIMESTAMP_SLACK_FRAMES = 2L
  }
}

/**
 * A stretch of one clip's output, planar, at the source's own rate.
 *
 * @property channels One array of samples per channel.
 * @property frames How many frames each channel holds.
 * @property sampleRate The rate those frames run at.
 * @property opensAtSeconds Where the first frame sits on the composition timeline. Two stretches of one clip join on
 * this exactly, since it is counted in whole frames from where the clip opened.
 */
internal class StretchedAudio(
  val channels: Array<FloatArray>,
  val frames: Int,
  val sampleRate: Int,
  val opensAtSeconds: Double,
) {
  /**
   * This stretch as a buffer of [context], which is the only context that may play it.
   */
  fun toBuffer(context: BaseAudioContext): AudioBuffer {
    val buffer = context.createBuffer(channels.size, frames, sampleRate.toFloat())
    channels.forEachIndexed { channel, samples -> buffer.copyToChannel(samples.toFloat32Array(), channel, 0) }
    return buffer
  }
}
