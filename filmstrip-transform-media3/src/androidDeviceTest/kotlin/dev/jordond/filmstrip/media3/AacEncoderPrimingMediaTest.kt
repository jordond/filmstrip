package dev.jordond.filmstrip.media3

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.media3.internal.encoder.EncoderColor
import dev.jordond.filmstrip.media3.internal.encoder.MeasuredEncoderColor
import dev.jordond.filmstrip.media3.internal.encoder.aacPrimingFrames
import dev.jordond.filmstrip.media3.internal.encoder.encoderFactory
import dev.jordond.filmstrip.media3.internal.encoder.pinnedEncoderFactory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * The priming an export's AAC encoder writes ahead of its stream, against the encoder delay its file names.
 *
 * Each run is silence with one click, encoded and decoded back with nothing trimmed. How far the click comes back late
 * is the priming. A file that names any other delay starts its audio that far off the start of the stream.
 */
class AacEncoderPrimingMediaTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun anEncoderPrimesTheFramesItsRuleGivesOnEveryApiLevel() {
    val sdkInt = Build.VERSION.SDK_INT
    assertSoftly {
      STREAMS.forEach { stream ->
        val encoded = encodeAac(stream, encoderFactory(context, null, null), ::clickAt)
        val priming = primingOf(stream, encoded)
        withClue(
          "$stream on ${encoded.name} on API $sdkInt primed $priming frames, its output named ${encoded.encoderDelay}",
        ) {
          priming shouldBe (aacPrimingFrames(encoded.name) ?: encoded.encoderDelay.toLong())
        }
      }
    }
  }

  @Test
  fun anExportNamesThePrimingItsEncoderWritesOnEveryApiLevel() {
    assertSoftly {
      STREAMS.forEach { stream ->
        val encoded = encodeAac(stream, exportFactory(), ::clickAt)
        val priming = primingOf(stream, encoded)
        withClue("$stream on ${encoded.name} on API ${Build.VERSION.SDK_INT} primed $priming frames") {
          encoded.encoderDelay.toLong() shouldBe priming
        }
      }
    }
  }

  private fun exportFactory() =
    EncoderColor(
      encoderFactory(context, null, null),
      { encoder -> pinnedEncoderFactory(context, encoder, null, null) },
      MeasuredEncoderColor.Device,
    )

  // How many frames after its own the click peaks in what the decoder gives back.
  private fun primingOf(
    stream: AacStream,
    encoded: EncodedAac,
  ): Long {
    val decoded = decodeAac(stream, encoded)
    return (decoded.indices.maxBy { abs(decoded[it].toInt()) } - CLICK_FRAME).toLong()
  }

  private fun clickAt(frame: Int): Short = if (frame == CLICK_FRAME) CLICK_LEVEL else 0

  private companion object {
    // Partway into an access unit, well clear of the head and the tail, at both rates and channel counts.
    val STREAMS = listOf(AacStream(24_000), AacStream(24_000, 44_100, 1))
    const val CLICK_FRAME = 5_000
    const val CLICK_LEVEL: Short = 16_000
  }
}
