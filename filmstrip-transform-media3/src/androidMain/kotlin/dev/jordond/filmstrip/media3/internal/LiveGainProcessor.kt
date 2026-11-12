package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.Util
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import java.nio.ByteBuffer

/**
 * Brings a clip to its level frame by frame off its resolved curve, which can be swapped while the stream plays.
 *
 * The stream keeps its format and its length, in 16-bit or float PCM. The processor stays in the graph at any level,
 * a held one included, so a standing preview can be handed a new curve without being rebuilt.
 *
 * media3 flushes a processor with how far into its stream the next frame sits, which for a clip is the time since its
 * first frame at the speed it plays at. A resolved curve is in the same time, so a swapped curve is read from the place
 * the stream has reached.
 *
 * A 16-bit sample is scaled the way media3's own `GainProcessor` scales it on an export, as `(short) (sample * gain)`.
 * That wraps rather than clipping, so a factor above one distorts a clip already near full scale here as it does there.
 *
 * @param gain The curve the stream opens with.
 */
internal class LiveGainProcessor(
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
            output.putFloat(inputBuffer.getFloat() * factor)
          } else {
            output.putShort((inputBuffer.getShort() * factor).toInt().toShort())
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
  }
}

/**
 * Every processor a preview brings a clip to its level with, by the stream it sits on.
 *
 * A plan lowered again puts a clip's processor at the same key, which is how a standing graph finds the one to hand
 * a changed level to.
 */
internal fun Composition.liveGainProcessors(): Map<KernelStream, LiveGainProcessor> =
  buildMap {
    sequences.forEachIndexed { sequence, items ->
      items.editedMediaItems.forEachIndexed { item, edited ->
        edited.effects.audioProcessors
          .firstNotNullOfOrNull { it as? LiveGainProcessor }
          ?.let { put(KernelStream(sequence, item), it) }
      }
    }
  }
