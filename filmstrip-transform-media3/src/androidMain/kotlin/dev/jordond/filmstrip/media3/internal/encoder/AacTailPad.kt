package dev.jordond.filmstrip.media3.internal.encoder

import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec

/**
 * A [Codec] that queues silence ahead of the end of the stream, so an AAC encoder that drops the end of its stream
 * still writes every frame it was given.
 *
 * Some of Android's software AAC encoders write only the whole access units their input fills, plus [tailUnits] more,
 * and drop the rest at the end of the stream. A flushing one writes every unit that holds its priming and the stream.
 * This one turns media3's end of stream into silence, then an empty end of stream, so the encoder writes as many
 * access units as a flushing one would. The silence is [aacTailPadFrames] frames less [tailUnits] whole units.
 * It goes into [delegate]'s own input buffers. The first takes the slot media3 dequeued for its end of stream, and the
 * rest are dequeued each time media3 asks for another buffer, which it keeps doing until the encoder ends. Media3 is
 * told no buffer is free once its end of stream is in. The stream is read as the PCM encoding the encoder was
 * configured with, or 16-bit PCM when it names none, which is what `MediaCodec` takes then.
 *
 * @param delegate The encoder the stream goes to.
 * @property tailUnits The access units [delegate] writes past the ones its input fills.
 */
internal class AacTailPad(
  private val delegate: Codec,
  val tailUnits: Long,
) : Codec by delegate {
  private val padBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
  private var fedBytes = 0L
  private var tail: Tail? = null

  override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
    val tail = tail ?: return delegate.maybeDequeueInputBuffer(inputBuffer)
    while (!tail.ended && delegate.maybeDequeueInputBuffer(padBuffer)) {
      queue(tail)
    }
    return false
  }

  override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
    if (!inputBuffer.isEndOfStream) {
      fedBytes += inputBuffer.data?.remaining() ?: 0
      delegate.queueInputBuffer(inputBuffer)
      return
    }
    check(inputBuffer.data?.hasRemaining() != true) { "media3's end of stream carried PCM, which the pad would drop" }
    val format = pcmFormatOf(delegate.configurationFormat)
    val fedFrames = fedBytes / format.bytesPerFrame
    val padFrames = aacTailPadFrames(fedFrames) - tailUnits * AAC_UNIT_FRAMES
    val started = Tail(format, inputBuffer.timeUs, padFrames * format.bytesPerFrame)
    tail = started
    padBuffer.data = inputBuffer.data
    inputBuffer.data = null
    queue(started)
  }

  private fun queue(tail: Tail) {
    padBuffer.clear()
    val data = checkNotNull(padBuffer.data)
    val frameBytes = tail.format.bytesPerFrame
    val bytes = minOf(tail.remainingBytes, data.remaining().toLong() / frameBytes * frameBytes)
    repeat(bytes.toInt()) { data.put(0) }
    data.flip()
    padBuffer.timeUs = tail.timeUs()
    if (bytes == 0L) {
      padBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
      tail.ended = true
    }
    tail.remainingBytes -= bytes
    tail.queuedBytes += bytes
    delegate.queueInputBuffer(padBuffer)
  }

  private fun pcmFormatOf(configured: Format): AudioFormat {
    val encoding = configured.pcmEncoding.takeIf { it != Format.NO_VALUE } ?: C.ENCODING_PCM_16BIT
    return AudioFormat(configured.sampleRate, configured.channelCount, encoding)
  }

  private class Tail(
    val format: AudioFormat,
    val startUs: Long,
    var remainingBytes: Long,
  ) {
    var queuedBytes = 0L
    var ended = false

    fun timeUs(): Long = startUs + queuedBytes / format.bytesPerFrame * C.MICROS_PER_SECOND / format.sampleRate
  }
}

/**
 * The frames of silence that fill [fedFrames] frames up to the access units a flushing encoder writes for them.
 *
 * A flushing encoder writes enough whole access units to hold its priming and the stream, and one that flushes nothing
 * writes the whole access units its input fills. Padded by this many frames, the two write the same count.
 */
internal fun aacTailPadFrames(fedFrames: Long): Long {
  val covered = fedFrames + AAC_PRIMING_FRAMES
  val units = (covered + AAC_UNIT_FRAMES - 1) / AAC_UNIT_FRAMES
  return units * AAC_UNIT_FRAMES - fedFrames
}

/**
 * The encoder [created] wrapped in an [AacTailPad] when [aacTailUnits] says it drops the end of its stream on [sdkInt],
 * or [created] itself otherwise.
 *
 * @param sdkInt The API level the encoder runs on.
 */
internal fun aacTailPadded(
  created: Codec,
  sdkInt: Int,
): Codec = aacTailUnits(created.name, sdkInt)?.let { AacTailPad(created, it) } ?: created

/**
 * The whole access units the AAC encoder named [encoder] writes on API level [sdkInt] past the ones its input fills,
 * or null when it writes every unit that holds its priming and the stream.
 *
 * `c2.android.aac.encoder` writes none on API 35 and below and flushes from API 36. `OMX.google.aac.encoder`, which
 * the factory picks on API 29 and below, writes one. Each was measured on an emulator at every API level from 24.
 */
internal fun aacTailUnits(
  encoder: String,
  sdkInt: Int,
): Long? =
  when {
    encoder == CODEC2_AAC_ENCODER && sdkInt <= Build.VERSION_CODES.VANILLA_ICE_CREAM -> 0
    encoder == OMX_AAC_ENCODER -> 1
    else -> null
  }

/**
 * The frames in one AAC-LC access unit.
 *
 * Every unit Android's software AAC encoder writes holds this many, at any sample rate and channel count.
 */
internal const val AAC_UNIT_FRAMES = 1_024L

/**
 * The frames of priming Android's software AAC encoder puts ahead of the stream, measured on API 36, where a stream of
 * any length comes out as the fewest whole access units that hold it and these frames.
 */
internal const val AAC_PRIMING_FRAMES = 2_048L

private const val CODEC2_AAC_ENCODER = "c2.android.aac.encoder"

private const val OMX_AAC_ENCODER = "OMX.google.aac.encoder"
