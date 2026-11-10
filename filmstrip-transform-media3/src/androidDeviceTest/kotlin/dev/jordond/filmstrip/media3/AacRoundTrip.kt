package dev.jordond.filmstrip.media3

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import dev.jordond.filmstrip.media3.internal.encoder.AAC_UNIT_FRAMES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.fail

/**
 * A stream of 16-bit PCM handed to an AAC encoder, every channel carrying the same samples.
 */
internal data class AacStream(
  val frames: Int,
  val sampleRate: Int = 48_000,
  val channels: Int = 2,
)

/**
 * What an AAC encoder wrote for a stream.
 *
 * @property name The encoder's name.
 * @property config The AudioSpecificConfig it wrote.
 * @property access Its access units, in order.
 * @property encoderDelay The encoder delay its output format names.
 */
internal class EncodedAac(
  val name: String,
  val config: ByteArray,
  val access: List<ByteArray>,
  val encoderDelay: Int,
)

/**
 * Encodes [stream] through an encoder [factory] makes, feeding it the way media3 does, with [sampleAt] giving each
 * frame's sample.
 */
internal fun encodeAac(
  stream: AacStream,
  factory: Codec.EncoderFactory,
  sampleAt: (Int) -> Short,
): EncodedAac {
  val format =
    Format
      .Builder()
      .setSampleMimeType(MimeTypes.AUDIO_AAC)
      .setSampleRate(stream.sampleRate)
      .setChannelCount(stream.channels)
      .setPcmEncoding(C.ENCODING_PCM_16BIT)
      .setAverageBitrate(BIT_RATE)
      .build()
  val codec = factory.createForAudioEncoding(format, null)
  val frameBytes = stream.channels * SAMPLE_BYTES
  val access = mutableListOf<ByteArray>()
  try {
    val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
    var fed = 0
    val deadline = System.nanoTime() + CODEC_BUDGET_NANOS
    // Asked for an input buffer until the encoder ends, as media3 does.
    while (!codec.isEnded) {
      if (System.nanoTime() >= deadline) fail("the encoder never ended its stream on ${stream.frames} frames")
      if (codec.maybeDequeueInputBuffer(input)) {
        val buffer = checkNotNull(input.data).order(ByteOrder.nativeOrder())
        val count = minOf(buffer.remaining() / frameBytes, stream.frames - fed, CHUNK_FRAMES)
        repeat(count) { index ->
          val sample = sampleAt(fed + index)
          repeat(stream.channels) { buffer.putShort(sample) }
        }
        buffer.flip()
        input.timeUs = fed * MICROS_PER_SECOND / stream.sampleRate
        fed += count
        if (count == 0) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        codec.queueInputBuffer(input)
      }
      val output = codec.outputBuffer ?: continue
      access += ByteArray(output.remaining()).also { output.get(it) }
      codec.releaseOutputBuffer(false)
    }
    val written = checkNotNull(codec.outputFormat)
    return EncodedAac(codec.name, written.initializationData.first(), access, written.encoderDelay)
  } finally {
    codec.release()
  }
}

/**
 * Every frame the platform's AAC decoder gives back for [encoded], first channel only, with nothing trimmed off its
 * head or its tail.
 */
internal fun decodeAac(
  stream: AacStream,
  encoded: EncodedAac,
): ShortArray {
  val format =
    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, stream.sampleRate, stream.channels).apply {
      setByteBuffer("csd-0", ByteBuffer.wrap(encoded.config))
    }
  val codec = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format))
  val samples = mutableListOf<Short>()
  try {
    codec.configure(format, null, null, 0)
    codec.start()
    var next = 0
    var ended = false
    val info = MediaCodec.BufferInfo()
    val deadline = System.nanoTime() + CODEC_BUDGET_NANOS
    while (true) {
      if (System.nanoTime() >=
        deadline
      ) {
        fail("the decoder never ended its stream on ${encoded.access.size} access units")
      }
      if (!ended) {
        val input = codec.dequeueInputBuffer(WAIT_MICROS)
        if (input >= 0) {
          val unit = encoded.access.getOrNull(next)
          if (unit == null) {
            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            ended = true
          } else {
            checkNotNull(codec.getInputBuffer(input)).put(unit)
            val timeUs = next * AAC_UNIT_FRAMES * MICROS_PER_SECOND / stream.sampleRate
            codec.queueInputBuffer(input, 0, unit.size, timeUs, 0)
            next++
          }
        }
      }
      val output = codec.dequeueOutputBuffer(info, WAIT_MICROS)
      if (output < 0) continue
      val buffer = checkNotNull(codec.getOutputBuffer(output)).order(ByteOrder.nativeOrder())
      val frameBytes = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * SAMPLE_BYTES
      for (frame in 0 until info.size / frameBytes) {
        samples += buffer.getShort(info.offset + frame * frameBytes)
      }
      codec.releaseOutputBuffer(output, false)
      if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
    }
    codec.stop()
  } finally {
    codec.release()
  }
  return samples.toShortArray()
}

private const val BIT_RATE = 128_000
private const val SAMPLE_BYTES = 2
private const val CHUNK_FRAMES = 1_024
private const val WAIT_MICROS = 10_000L
private const val CODEC_BUDGET_NANOS = 30_000_000_000L
private const val MICROS_PER_SECOND = 1_000_000L
