package dev.jordond.filmstrip.media3

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
 * What the platform's AAC encoder does with the end of a stream, through `MediaCodec` alone.
 *
 * Each run is silence with a tone over its last frames, encoded and decoded back. On API 30 the encoder writes only the
 * whole access units it was fed and flushes nothing, so the tone never reaches the file. On API 36 the tone comes back.
 * An export's audio reads short on API 30 because of this, and media3 plays no part in it.
 */
class AacEncoderFlushTest {
  @Test
  fun anEncoderKeepsOrDropsTheEndOfTheStreamByApiLevel() {
    INPUT_FRAMES.forEach { frames ->
      val units = encode(frames)
      val tone = decodedToneFrames(units)
      withClue("$frames frames made ${units.access.size} access units and $tone decoded frames of the tone") {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
          units.access.size shouldBe frames / UNIT_FRAMES
          tone shouldBe 0
        } else {
          (units.access.size * UNIT_FRAMES >= frames).shouldBeTrue()
          (tone > 0).shouldBeTrue()
        }
      }
    }
  }

  private fun encode(frames: Int): EncodedUnits {
    val format =
      MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      }
    val codec = MediaCodec.createByCodecName(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format))
    val access = mutableListOf<ByteArray>()
    var config = ByteArray(0)
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      var fed = 0
      var ended = false
      val info = MediaCodec.BufferInfo()
      val deadline = System.nanoTime() + CODEC_BUDGET_NANOS
      while (true) {
        if (System.nanoTime() >= deadline) fail("the encoder never ended its stream on $frames frames")
        if (!ended) {
          val input = codec.dequeueInputBuffer(WAIT_MICROS)
          if (input >= 0) {
            val buffer = checkNotNull(codec.getInputBuffer(input)).order(ByteOrder.nativeOrder())
            val count = minOf(buffer.remaining() / FRAME_BYTES, frames - fed, CHUNK_FRAMES)
            repeat(count) { index -> buffer.putFrame(sampleAt(fed + index, frames)) }
            val timeUs = fed * MICROS_PER_SECOND / SAMPLE_RATE
            fed += count
            ended = count == 0
            val flags = if (ended) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            codec.queueInputBuffer(input, 0, count * FRAME_BYTES, timeUs, flags)
          }
        }
        val output = codec.dequeueOutputBuffer(info, WAIT_MICROS)
        if (output < 0) continue
        val bytes = ByteArray(info.size)
        checkNotNull(codec.getOutputBuffer(output)).get(bytes)
        when {
          info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> config = bytes
          bytes.isNotEmpty() -> access += bytes
        }
        codec.releaseOutputBuffer(output, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
      }
      codec.stop()
    } finally {
      codec.release()
    }
    return EncodedUnits(config, access)
  }

  private fun decodedToneFrames(units: EncodedUnits): Int {
    val format =
      MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
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
              codec.queueInputBuffer(input, 0, unit.size, next * UNIT_FRAMES * MICROS_PER_SECOND / SAMPLE_RATE, 0)
              next++
            }
          }
        }
        val output = codec.dequeueOutputBuffer(info, WAIT_MICROS)
        if (output < 0) continue
        val buffer = checkNotNull(codec.getOutputBuffer(output)).order(ByteOrder.nativeOrder())
        for (frame in 0 until info.size / FRAME_BYTES) {
          if (abs(buffer.getShort(info.offset + frame * FRAME_BYTES).toInt()) > TONE_FLOOR) tone++
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
    frames: Int,
  ): Short =
    if (frame < frames - TONE_FRAMES) {
      0
    } else {
      (sin(2 * PI * TONE_HZ * frame / SAMPLE_RATE) * TONE_LEVEL).toInt().toShort()
    }

  private fun ByteBuffer.putFrame(sample: Short) {
    putShort(sample)
    putShort(sample)
  }

  private class EncodedUnits(
    val config: ByteArray,
    val access: List<ByteArray>,
  )

  private companion object {
    // Under, between and past whole access units, and the frames a 2 s clip at 0.7 pins its audio to.
    val INPUT_FRAMES = listOf(18_000, 48_000, 137_143)
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BIT_RATE = 128_000
    const val FRAME_BYTES = 4
    const val UNIT_FRAMES = 1_024
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
