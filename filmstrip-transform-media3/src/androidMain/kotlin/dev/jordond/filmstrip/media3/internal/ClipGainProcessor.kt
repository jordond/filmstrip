package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Brings a clip to its level frame by frame off its resolved curve, which can be swapped while the stream plays.
 *
 * The stream keeps its format and its length, in 16-bit or float PCM. An export and a preview both scale through this
 * processor, so a level sounds the same in each. A preview keeps it in the graph at any level, a held one included, so
 * a standing preview can be handed a new curve without being rebuilt.
 *
 * media3 flushes a processor with how far into its stream the next frame sits, which for a clip is the time since its
 * first frame at the speed it plays at. A resolved curve is in the same time, so a swapped curve is read from the place
 * the stream has reached.
 *
 * A sample a factor above one carries past full scale is held at full scale, in both formats.
 *
 * @param gain The curve the stream opens with.
 */
internal class ClipGainProcessor(
  gain: ResolvedGain,
) : BaseAudioProcessor() {
  /**
   * The curve the stream is scaled by from its next block on.
   */
  @Volatile
  var gain: ResolvedGain = gain
    private set

  private var position = 0L

  /**
   * Scales the stream by [gain] from its next block on, in place of the curve it has run so far.
   *
   * May be called from any thread. A stream that is already running keeps its place in the clip.
   */
  fun swap(gain: ResolvedGain) {
    this.gain = gain
  }

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    when (inputAudioFormat.encoding) {
      C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
      else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return
    val format = inputAudioFormat
    val frames = inputBuffer.remaining() / format.bytesPerFrame
    val output = replaceOutputBuffer(frames * format.bytesPerFrame)
    val curve = gain
    val held = curve.constant
    if (held == UNITY) {
      val whole = inputBuffer.limit()
      inputBuffer.limit(inputBuffer.position() + frames * format.bytesPerFrame)
      output.put(inputBuffer)
      inputBuffer.limit(whole)
    } else {
      val float = format.encoding == C.ENCODING_PCM_FLOAT
      for (frame in 0 until frames) {
        val factor = held ?: curve.gainAt(clipTimeOf(position + frame, format.sampleRate))
        repeat(format.channelCount) {
          if (float) {
            output.putFloat((inputBuffer.getFloat() * factor).coerceIn(-FLOAT_FULL_SCALE, FLOAT_FULL_SCALE))
          } else {
            val scaled = (inputBuffer.getShort() * factor).toInt()
            output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
          }
        }
      }
    }
    position += frames
    output.flip()
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    val format = inputAudioFormat
    if (format == AudioProcessor.AudioFormat.NOT_SET) return
    position = Util.durationUsToSampleCount(streamMetadata.positionOffsetUs, format.sampleRate)
  }

  override fun onReset() {
    position = 0L
  }

  private companion object {
    const val UNITY = 1f
    const val FLOAT_FULL_SCALE = 1f
  }
}

/**
 * Every processor a preview brings a clip to its level with, by the stream it sits on.
 *
 * A plan lowered again puts a clip's processor at the same key, which is how a standing graph finds the one to hand
 * a changed level to.
 */
internal fun Composition.liveGainProcessors(): Map<KernelStream, ClipGainProcessor> =
  buildMap {
    sequences.forEachIndexed { sequence, items ->
      items.editedMediaItems.forEachIndexed { item, edited ->
        edited.effects.audioProcessors
          .firstNotNullOfOrNull { it as? ClipGainProcessor }
          ?.let { put(KernelStream(sequence, item), it) }
      }
    }
  }

// The clip time a frame position sits at, split at the second so a long stream cannot overflow the
// nanosecond conversion.
internal fun clipTimeOf(
  frames: Long,
  sampleRate: Int,
): Duration = (frames / sampleRate).seconds + ((frames % sampleRate) * NANOS_PER_SECOND / sampleRate).nanoseconds

private const val NANOS_PER_SECOND = 1_000_000_000L
