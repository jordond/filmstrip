package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import dev.jordond.filmstrip.transform.internal.resolve.runsAs
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Runs a list of audio effects over one stream through a [KernelChain], in 16-bit or float PCM.
 *
 * The stream keeps its format and its length. The frames the kernels' latency holds back come out once the stream
 * ends.
 *
 * media3 flushes a processor with how far into its stream the next frame sits. For a clip that is the time since the
 * clip's first frame at the speed it plays at, and for a composition it is the composition's own time, so [start] is
 * where the stream opens on the timeline and every flush lands the kernels on [timelineFrameOf] of the two together.
 *
 * @param effects The effects to run, first to last.
 * @param start The composition time the stream's first frame plays at.
 */
internal class KernelAudioProcessor(
  effects: List<ResolvedAudioEffect>,
  val start: Duration,
) : BaseAudioProcessor() {
  private val lock = Any()

  /**
   * The effects the stream runs from its next block on.
   */
  var effects: List<ResolvedAudioEffect> = effects
    private set

  private var chain: KernelChain? = null
  private var chainFormat: AudioProcessor.AudioFormat? = null
  private var shorts = ShortArray(0)
  private var floats = FloatArray(0)
  private var ended = false
  private var drained = false

  /**
   * Runs [effects] from the next block on, in place of the ones the stream has run so far.
   *
   * May be called from any thread. A stream that is already running keeps its place on the timeline, and keeps its
   * kernels and what they have built up when [effects] are the ones it already runs.
   */
  fun swap(effects: List<ResolvedAudioEffect>) {
    synchronized(lock) {
      val unchanged = effects.runsAs(this.effects)
      this.effects = effects
      if (unchanged) return
      val format = chainFormat ?: return
      chain?.swap(effects.kernelsFor(format))
    }
  }

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    when (inputAudioFormat.encoding) {
      C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
      else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
    if (frames == 0) return
    val chain = checkNotNull(chain) { "The processor was handed audio before it was flushed." }
    val samples = frames * inputAudioFormat.channelCount
    if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
      val block = floatsFor(samples)
      for (index in 0 until samples) block[index] = inputBuffer.getFloat()
      writeFloats(block, chain.process(block, frames))
    } else {
      val block = shortsFor(samples)
      for (index in 0 until samples) block[index] = inputBuffer.getShort()
      writeShorts(block, chain.process(block, frames))
    }
  }

  override fun getOutput(): ByteBuffer {
    val chain = chain
    if (ended && !drained && chain != null && !hasPendingOutput()) {
      val samples = DRAIN_FRAMES * inputAudioFormat.channelCount
      val frames =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
          val block = floatsFor(samples)
          chain.drain(block, DRAIN_FRAMES).also { writeFloats(block, it) }
        } else {
          val block = shortsFor(samples)
          chain.drain(block, DRAIN_FRAMES).also { writeShorts(block, it) }
        }
      drained = frames == 0
    }
    return super.getOutput()
  }

  override fun isEnded(): Boolean = super.isEnded() && (drained || chain == null)

  override fun onQueueEndOfStream() {
    ended = true
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    ended = false
    drained = false
    val format = inputAudioFormat
    // media3 flushes a composition's processors once before it knows the format of the mix.
    if (format == AudioProcessor.AudioFormat.NOT_SET) return
    val opens = timelineFrameOf(start + streamMetadata.positionOffsetUs.microseconds, format.sampleRate)
    synchronized(lock) {
      val standing = chain
      if (standing != null && chainFormat == format) {
        standing.reset(opens)
      } else {
        chain = KernelChain(effects.kernelsFor(format), format.channelCount, opens)
        chainFormat = format
      }
    }
  }

  override fun onReset() {
    synchronized(lock) {
      chain = null
      chainFormat = null
    }
    ended = false
    drained = false
  }

  private fun writeShorts(
    block: ShortArray,
    frames: Int,
  ) {
    val samples = frames * inputAudioFormat.channelCount
    val output = replaceOutputBuffer(samples * Short.SIZE_BYTES)
    for (index in 0 until samples) output.putShort(block[index])
    output.flip()
  }

  private fun writeFloats(
    block: FloatArray,
    frames: Int,
  ) {
    val samples = frames * inputAudioFormat.channelCount
    val output = replaceOutputBuffer(samples * Float.SIZE_BYTES)
    for (index in 0 until samples) output.putFloat(block[index])
    output.flip()
  }

  private fun shortsFor(samples: Int): ShortArray {
    if (shorts.size < samples) shorts = ShortArray(samples)
    return shorts
  }

  private fun floatsFor(samples: Int): FloatArray {
    if (floats.size < samples) floats = FloatArray(samples)
    return floats
  }

  private companion object {
    const val DRAIN_FRAMES = 4_096
  }
}

/**
 * The processor that runs [effects] over a stream opening at [start], or none when there is nothing to run.
 */
internal fun kernelProcessors(
  effects: List<ResolvedAudioEffect>,
  start: Duration,
): List<AudioProcessor> = if (effects.isEmpty()) emptyList() else listOf(KernelAudioProcessor(effects, start))

/**
 * Which stream of a composition a processor runs audio effects on: an item of a sequence, or the mix when [sequence]
 * is null.
 */
internal data class KernelStream(
  val sequence: Int?,
  val item: Int = 0,
)

/**
 * Every processor running audio effects in this composition, by the stream it sits on.
 *
 * A plan lowered again puts a stream's processor at the same key, so a standing graph can tell the one to hand a
 * change to from one on another track that happens to open at the same time.
 */
internal fun Composition.kernelProcessors(): Map<KernelStream, KernelAudioProcessor> =
  buildMap {
    sequences.forEachIndexed { sequence, items ->
      items.editedMediaItems.forEachIndexed { item, edited ->
        edited.effects.audioProcessors
          .kernelProcessor()
          ?.let { put(KernelStream(sequence, item), it) }
      }
    }
    effects.audioProcessors.kernelProcessor()?.let { put(KernelStream(sequence = null), it) }
  }

private fun List<AudioProcessor>.kernelProcessor(): KernelAudioProcessor? =
  firstNotNullOfOrNull { it as? KernelAudioProcessor }

private fun List<ResolvedAudioEffect>.kernelsFor(format: AudioProcessor.AudioFormat): List<AudioKernel> {
  val stream = AudioFormat(format.sampleRate, format.channelCount)
  return map { it.effect.newKernel(stream) }
}
