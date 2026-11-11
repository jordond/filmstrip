package dev.jordond.filmstrip.webcodecs.internal.audio

import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.resolve.runsAs
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.OfflineAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlinx.coroutines.await
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The mix rendered a window after another from one place on the timeline, carrying what has to join across them:
 * the stretch of every clip a boundary cut and the kernels of the composition's audio effects.
 *
 * @param origin The frame of the timeline the first window opens at, counted at [format]'s rate.
 */
internal class MixWindows(
  private val format: AudioFormat,
  private val sources: SourceCache,
  private val origin: Int,
  private val decoded: DecodeCache = DecodeCache(),
) {
  private val rate = format.sampleRate
  private val pad = BrowserAudioMix.frames(BrowserAudioMix.PAD, rate)
  private val stretches = mutableMapOf<Pass, ClipStretch>()
  private var adopted: List<ResolvedAudioEffect> = emptyList()
  private var chain: KernelChain? = null
  private var last: OfflineAudioContext? = null

  /**
   * Renders the [length] frames of [tracks] that open at frame [start], which is where the window before closed,
   * then runs [mixEffects] over them.
   *
   * @return The next frames of the mix, or null when the effects' latency held all of them back. What is held comes
   * out of later windows and of [drain].
   */
  suspend fun render(
    tracks: List<ResolvedTrack>,
    mixEffects: List<ResolvedAudioEffect>,
    start: Int,
    length: Int,
  ): AudioBuffer? {
    val lead = minOf(pad, start - origin)
    val context = OfflineAudioContext(format.channelCount, lead + length, rate.toFloat())
    val span =
      BrowserAudioMix.MixSpan(
        context = context,
        from = (start - lead).toDouble() / rate,
        audibleFrom = start.toDouble() / rate,
        to = (start + length).toDouble() / rate,
        opens = (origin.toDouble() / rate).toDuration(DurationUnit.SECONDS),
      )
    last = context
    decoded.clear()

    BrowserAudioMix.placed(tracks).forEach { clip -> BrowserAudioMix.place(clip, span, sources, decoded, stretches) }

    val rendered = context.startRendering().await()
    val window = if (lead == 0) rendered else BrowserAudioMix.withoutLead(context, rendered, lead, length)
    adopt(mixEffects, start)
    val running = chain ?: return window
    val samples = interleaved(window)
    val written = running.process(samples, length)
    return if (written == 0) null else bufferOf(context, samples, written)
  }

  /**
   * The next frames the composition's effects still hold once the last window is rendered, or null when none are
   * left.
   */
  fun drain(): AudioBuffer? {
    val running = chain ?: return null
    val context = last ?: return null
    val samples = FloatArray(DRAIN_FRAMES * format.channelCount)
    val written = running.drain(samples, DRAIN_FRAMES)
    return if (written == 0) null else bufferOf(context, samples, written)
  }

  fun close() {
    stretches.values.forEach { it.close() }
    stretches.clear()
  }

  private fun adopt(
    effects: List<ResolvedAudioEffect>,
    start: Int,
  ) {
    if (effects.runsAs(adopted)) return
    adopted = effects
    val kernels = effects.map { it.effect.newKernel(format) }
    val running = chain
    when {
      running != null -> running.swap(kernels)
      kernels.isNotEmpty() -> chain = KernelChain(kernels, format.channelCount, start.toLong())
    }
  }

  private fun interleaved(buffer: AudioBuffer): FloatArray {
    val channels = format.channelCount
    val samples = FloatArray(buffer.length * channels)
    for (channel in 0 until channels) {
      val data = buffer.getChannelData(channel)
      for (index in 0 until buffer.length) samples[index * channels + channel] = data.at(index)
    }
    return samples
  }

  private fun bufferOf(
    context: OfflineAudioContext,
    samples: FloatArray,
    frames: Int,
  ): AudioBuffer {
    val channels = format.channelCount
    val buffer = context.createBuffer(channels, frames, rate.toFloat())
    for (channel in 0 until channels) {
      val plane = FloatArray(frames) { samples[it * channels + channel] }
      buffer.copyToChannel(plane.toFloat32Array(), channel, 0)
    }
    return buffer
  }

  private companion object {
    // How many frames of what the composition's effects hold are handed on at a time.
    const val DRAIN_FRAMES = 4_096
  }
}

/**
 * What says two resolved clips are the same pass of the same track, which holds across a parameter swap that
 * resolves them all again.
 */
internal data class Pass(
  val track: Int,
  val index: Int,
  val source: MediaSource,
  val audioTrack: AudioTrackId?,
  val start: Duration,
  val end: Duration,
  val opensAt: Duration,
  val speed: Double,
)
