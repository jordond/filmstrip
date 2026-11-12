@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.runsAs
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsNonInterleaved
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.MediaToolbox.MTAudioProcessingTapGetSourceAudio
import platform.MediaToolbox.MTAudioProcessingTapRef
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The kernels of every span at the format a tap was prepared for, and the buffer a block is run through them in.
 *
 * AVFoundation places each block a tap hands back at the time of the source it asked for, and a player hands a tap no
 * source past that block. So the kernels run on the block alone and every frame goes back the moment it comes, which
 * plays a track late by what its kernels hold back. That is AVFoundation's limit.
 */
internal class TapStage private constructor(
  private val format: AudioFormat,
  private val planar: Boolean,
  private val maxFrames: Int,
  spans: List<TapSpan>,
  mix: TapMix?,
) {
  private val channels = format.channelCount
  private val starts = LongArray(spans.size) { timelineFrameOf(spans[it].range.start, format.sampleRate) }
  private val ends =
    LongArray(spans.size) { index ->
      spans[index].range.endExclusive?.let { timelineFrameOf(it, format.sampleRate) } ?: Long.MAX_VALUE
    }
  private val chains = Array(spans.size) { index -> chainOf(spans[index].effects, starts[index]) }

  // The effects each span and the mix are running, which a swap is compared with. Only the thread that swaps reads
  // or writes them.
  private val effects = Array(spans.size) { spans[it].effects }
  private var mixEffects = mix?.effects.orEmpty()

  private val block = FloatArray(maxFrames * channels)

  @Volatile
  private var gains: Array<GainCurve?> = gainsOf(spans)

  private val mixChain = mix?.let { chainOf(it.effects, 0) }

  // The mix's effects run over silence beside the track's own audio, on a track that leaves their own sound out.
  private val silentChain = mix?.takeUnless { it.carriesAdditive }?.let { chainOf(it.effects, 0) }
  private val silence = if (silentChain != null) FloatArray(maxFrames * channels) else EMPTY

  // The timeline frame the next block opens on when the track plays on without a seek, and the span its last frame
  // was in.
  private var next = Long.MIN_VALUE
  private var current = -1

  /**
   * Swaps in the kernels and gains of [spans] where a span opens on the frame a standing one does, and the kernels of
   * [mix]. Effects that are the ones already running keep their kernels.
   */
  fun swap(
    spans: List<TapSpan>,
    mix: TapMix?,
  ) {
    if (mix != null && !mix.effects.runsAs(mixEffects)) {
      mixEffects = mix.effects
      mixChain?.swap(mix.effects.map { it.effect.newKernel(format) })
      silentChain?.swap(mix.effects.map { it.effect.newKernel(format) })
    }
    if (spans.size != chains.size) return
    val curves = gains.copyOf()
    spans.forEachIndexed { index, span ->
      if (timelineFrameOf(span.range.start, format.sampleRate) == starts[index]) {
        if (!span.effects.runsAs(effects[index])) {
          effects[index] = span.effects
          chains[index].swap(span.effects.map { it.effect.newKernel(format) })
        }
        curves[index] = span.gain?.let { GainCurve.of(it, format.sampleRate) }
      }
    }
    gains = curves
  }

  /**
   * Reads the source of the next [frames] frames into [buffers] and leaves what the spans make of it there.
   *
   * @return how many frames [buffers] now holds, or a negative number when AVFoundation handed over no source.
   */
  fun run(
    tap: MTAudioProcessingTapRef?,
    frames: Int,
    buffers: CPointer<AudioBufferList>,
    scratch: TapScratch,
  ): Int {
    if (!scratch.read(tap, frames, buffers)) return NO_SOURCE
    val read = scratch.frames.value.toInt()
    val opens = timelineFrameOf(scratch.range.start.value, scratch.range.start.timescale)
    val seeked = opens != next
    if (seeked) current = -1
    next = opens + read

    spanned(buffers, opens, read)
    if (mixChain != null) mixed(buffers, read, opens, seeked)
    return read
  }

  // Runs each of the [frames] frames in [buffers], which open at the timeline frame [opens], through the span it sits
  // in and applies the span's gain to what its kernels wrote. Audio outside every span stays as it was read.
  private fun spanned(
    buffers: CPointer<AudioBufferList>,
    opens: Long,
    frames: Int,
  ) {
    val until = opens + frames
    var at = opens
    while (at < until) {
      val span = spanAt(at)
      val edge = if (span >= 0) ends[span] else nextStartAfter(at)
      val count = (minOf(edge, until) - at).toInt()
      if (span >= 0) {
        if (span != current) chains[span].reset(at)
        val from = (at - opens).toInt()
        load(buffers, from, count)
        chains[span].process(block, count)
        gains[span]?.apply(block, count, channels, at - starts[span])
        store(buffers, from, count)
      }
      current = if (span >= 0 && at + count == ends[span]) -1 else span
      at += count
    }
  }

  // Runs the mix's effects over the [frames] frames the spans left in [buffers], which open at the timeline frame
  // [opens].
  private fun mixed(
    buffers: CPointer<AudioBufferList>,
    frames: Int,
    opens: Long,
    seeked: Boolean,
  ) {
    val chain = mixChain ?: return
    if (seeked) {
      chain.reset(opens)
      silentChain?.reset(opens)
    }
    load(buffers, 0, frames)
    chain.process(block, frames)
    silentChain?.let { silent ->
      // What the effects write over silence is taken away again, on a track that leaves it out.
      silence.fill(0f, 0, frames * channels)
      silent.process(silence, frames)
      for (index in 0 until frames * channels) block[index] -= silence[index]
    }
    store(buffers, 0, frames)
  }

  private fun spanAt(frame: Long): Int {
    for (index in starts.indices) if (frame >= starts[index] && frame < ends[index]) return index
    return -1
  }

  private fun nextStartAfter(frame: Long): Long {
    for (index in starts.indices) if (starts[index] > frame) return starts[index]
    return Long.MAX_VALUE
  }

  private fun chainOf(
    effects: List<ResolvedAudioEffect>,
    start: Long,
  ): KernelChain =
    KernelChain(effects.map { it.effect.newKernel(format) }, channels, start, maxFrames, compensates = false)

  private fun gainsOf(spans: List<TapSpan>): Array<GainCurve?> =
    Array(spans.size) { index -> spans[index].gain?.let { GainCurve.of(it, format.sampleRate) } }

  private fun timelineFrameOf(
    value: Long,
    timescale: Int,
  ): Long {
    if (timescale <= 0) return 0
    val whole = value.floorDiv(timescale)
    val rest = (value - whole * timescale) * NANOS_PER_SECOND / timescale
    return timelineFrameOf(whole.seconds + rest.nanoseconds, format.sampleRate)
  }

  // Puts [frames] frames of [input] from [from] at the head of the block.
  private fun load(
    input: CPointer<AudioBufferList>,
    from: Int,
    frames: Int,
  ) {
    val list = input.pointed
    if (planar) {
      for (channel in 0 until channels) {
        val data = list.mBuffers[channel].mData?.reinterpret<FloatVar>() ?: continue
        for (frame in 0 until frames) block[frame * channels + channel] = data[from + frame]
      }
    } else {
      val data = list.mBuffers[0].mData?.reinterpret<FloatVar>() ?: return
      for (index in 0 until frames * channels) block[index] = data[from * channels + index]
    }
  }

  private fun store(
    buffers: CPointer<AudioBufferList>,
    at: Int,
    frames: Int,
  ) {
    val list = buffers.pointed
    if (planar) {
      for (channel in 0 until channels) {
        val data = list.mBuffers[channel].mData?.reinterpret<FloatVar>() ?: continue
        for (frame in 0 until frames) data[at + frame] = block[frame * channels + channel]
      }
    } else {
      val data = list.mBuffers[0].mData?.reinterpret<FloatVar>() ?: return
      for (index in 0 until frames * channels) data[at * channels + index] = block[index]
    }
  }

  companion object {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NO_SOURCE = -1

    /**
     * The stage for [format], or null when it is not the 32-bit float PCM a tap is documented to process in, in which
     * case the track's audio passes through.
     */
    fun of(
      spans: List<TapSpan>,
      mix: TapMix?,
      maxFrames: Int,
      format: AudioStreamBasicDescription,
    ): TapStage? {
      val float = format.mFormatFlags and kAudioFormatFlagIsFloat != 0u
      if (format.mFormatID != kAudioFormatLinearPCM || !float || format.mBitsPerChannel != FLOAT_BITS) return null
      return TapStage(
        format = AudioFormat(format.mSampleRate.toInt(), format.mChannelsPerFrame.toInt()),
        planar = format.mFormatFlags and kAudioFormatFlagIsNonInterleaved != 0u,
        maxFrames = maxFrames,
        spans = spans,
        mix = mix,
      )
    }

    private const val FLOAT_BITS = 32u
    private val EMPTY = FloatArray(0)
  }
}

/**
 * Reads the source audio of the next [frames] frames into [buffers], leaving how many came, their flags and their
 * time range in this scratch.
 *
 * @return false when AVFoundation handed over nothing.
 */
internal fun TapScratch.read(
  tap: MTAudioProcessingTapRef?,
  frames: Int,
  buffers: CPointer<AudioBufferList>,
): Boolean =
  MTAudioProcessingTapGetSourceAudio(tap, frames.toLong(), buffers, flags.ptr, range.ptr, this.frames.ptr) == 0
