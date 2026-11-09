package dev.jordond.filmstrip.media3

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.media3.internal.encoder.AAC_UNIT_FRAMES
import dev.jordond.filmstrip.media3.internal.encoder.EncoderColor
import dev.jordond.filmstrip.media3.internal.encoder.MeasuredEncoderColor
import dev.jordond.filmstrip.media3.internal.encoder.aacTailPadFrames
import dev.jordond.filmstrip.media3.internal.encoder.aacTailUnits
import dev.jordond.filmstrip.media3.internal.encoder.encoderFactory
import dev.jordond.filmstrip.media3.internal.encoder.pinnedEncoderFactory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.fail

/**
 * What an export's AAC encoder does with the end of a stream, made by the export's own encoder factory.
 *
 * Each run is silence with a tone over its last frames, encoded and decoded back. Android's software AAC encoders drop
 * the end of the stream on API 35 and below and the factory pads them, so on every API level the encoder writes the
 * access units that hold its priming and every frame, and the tone comes back. Unpadded, each encoder writes the
 * access units [aacTailUnits] gives for it.
 */
class AacEncoderFlushMediaTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun anEncoderKeepsTheEndOfTheStreamOnEveryApiLevel() {
    STREAMS.forEach { stream ->
      val units = encode(stream, exportFactory())
      val tone = decodedToneFrames(stream, units)
      withClue("$stream made ${units.access.size} access units on ${units.name} and $tone decoded frames of the tone") {
        units.access.size.toLong() shouldBe (stream.frames + aacTailPadFrames(stream.frames.toLong())) / AAC_UNIT_FRAMES
        (tone > 0).shouldBeTrue()
      }
    }
  }

  @Test
  fun anUnpaddedEncoderWritesTheUnitsItsTailRuleGives() {
    val sdkInt = Build.VERSION.SDK_INT
    assertSoftly {
      STREAMS.forEach { stream ->
        val units = encode(stream, encoderFactory(context, null))
        val flushed = (stream.frames + aacTailPadFrames(stream.frames.toLong())) / AAC_UNIT_FRAMES
        val filled = stream.frames / AAC_UNIT_FRAMES
        withClue("$stream made ${units.access.size} access units on ${units.name} unpadded on API $sdkInt") {
          units.access.size.toLong() shouldBe (aacTailUnits(units.name, sdkInt)?.let { filled + it } ?: flushed)
        }
      }
    }
  }

  private fun exportFactory(): Codec.EncoderFactory =
    EncoderColor(
      encoderFactory(context, null),
      { encoder -> pinnedEncoderFactory(context, encoder, null) },
      MeasuredEncoderColor.Device,
    )

  private fun encode(
    stream: Stream,
    factory: Codec.EncoderFactory,
  ): EncodedUnits {
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
    val frames = stream.frames
    val frameBytes = stream.channels * SAMPLE_BYTES
    val access = mutableListOf<ByteArray>()
    try {
      val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
      var fed = 0
      val deadline = System.nanoTime() + CODEC_BUDGET_NANOS
      // Asked for an input buffer until the encoder ends, as media3 does.
      while (!codec.isEnded) {
        if (System.nanoTime() >= deadline) fail("the encoder never ended its stream on $frames frames")
        if (codec.maybeDequeueInputBuffer(input)) {
          val buffer = checkNotNull(input.data).order(ByteOrder.nativeOrder())
          val count = minOf(buffer.remaining() / frameBytes, frames - fed, CHUNK_FRAMES)
          repeat(count) { index ->
            val sample = sampleAt(fed + index, stream)
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
      val config = checkNotNull(codec.outputFormat).initializationData.first()
      return EncodedUnits(codec.name, config, access)
    } finally {
      codec.release()
    }
  }

  private fun decodedToneFrames(
    stream: Stream,
    units: EncodedUnits,
  ): Int {
    val format =
      MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, stream.sampleRate, stream.channels).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(units.config))
      }
    val codec = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format))
    var tone = 0
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
          fail("the decoder never ended its stream on ${units.access.size} access units")
        }
        if (!ended) {
          val input = codec.dequeueInputBuffer(WAIT_MICROS)
          if (input >= 0) {
            val unit = units.access.getOrNull(next)
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
          if (abs(buffer.getShort(info.offset + frame * frameBytes).toInt()) > TONE_FLOOR) tone++
        }
        codec.releaseOutputBuffer(output, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
      }
      codec.stop()
    } finally {
      codec.release()
    }
    return tone
  }

  private fun sampleAt(
    frame: Int,
    stream: Stream,
  ): Short =
    if (frame < stream.frames - TONE_FRAMES) {
      0
    } else {
      (sin(2 * PI * TONE_HZ * frame / stream.sampleRate) * TONE_LEVEL).toInt().toShort()
    }

  private data class Stream(
    val frames: Int,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
  )

  private class EncodedUnits(
    val name: String,
    val config: ByteArray,
    val access: List<ByteArray>,
  )

  private companion object {
    // On and one past a whole access unit, under, between and past them, and the frames a 2 s clip at 0.7 pins its
    // audio to, then 44.1 kHz mono on and past a unit and over a second.
    val STREAMS =
      listOf(
        Stream(1_024),
        Stream(1_025),
        Stream(18_000),
        Stream(48_000),
        Stream(137_143),
        Stream(1_024, 44_100, 1),
        Stream(1_025, 44_100, 1),
        Stream(44_100, 44_100, 1),
      )
    const val BIT_RATE = 128_000
    const val SAMPLE_BYTES = 2
    const val CHUNK_FRAMES = 1_024
    const val WAIT_MICROS = 10_000L
    const val CODEC_BUDGET_NANOS = 30_000_000_000L
    const val MICROS_PER_SECOND = 1_000_000L
    const val TONE_FRAMES = 512
    const val TONE_HZ = 440
    const val TONE_LEVEL = 16_000
    const val TONE_FLOOR = 300
  }
}
