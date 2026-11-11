package dev.jordond.filmstrip.media3

import android.content.Context
import android.os.Build
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test

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
        val units = encode(stream, encoderFactory(context, null, null))
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
      encoderFactory(context, null, null),
      { encoder -> pinnedEncoderFactory(context, encoder, null, null) },
      MeasuredEncoderColor.Device,
    )

  private fun encode(
    stream: AacStream,
    factory: Codec.EncoderFactory,
  ): EncodedAac = encodeAac(stream, factory) { sampleAt(it, stream) }

  private fun decodedToneFrames(
    stream: AacStream,
    units: EncodedAac,
  ): Int = decodeAac(stream, units).count { abs(it.toInt()) > TONE_FLOOR }

  private fun sampleAt(
    frame: Int,
    stream: AacStream,
  ): Short =
    if (frame < stream.frames - TONE_FRAMES) {
      0
    } else {
      (sin(2 * PI * TONE_HZ * frame / stream.sampleRate) * TONE_LEVEL).toInt().toShort()
    }

  private companion object {
    // On and one past a whole access unit, under, between and past them, and the frames a 2 s clip at 0.7 pins its
    // audio to, then 44.1 kHz mono on and past a unit and over a second.
    val STREAMS =
      listOf(
        AacStream(1_024),
        AacStream(1_025),
        AacStream(18_000),
        AacStream(48_000),
        AacStream(137_143),
        AacStream(1_024, 44_100, 1),
        AacStream(1_025, 44_100, 1),
        AacStream(44_100, 44_100, 1),
      )
    const val TONE_FRAMES = 512
    const val TONE_HZ = 440
    const val TONE_LEVEL = 16_000
    const val TONE_FLOOR = 300
  }
}
