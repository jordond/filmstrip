package dev.jordond.filmstrip.media3

import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MimeTypes
import dev.jordond.filmstrip.media3.internal.encoder.EncoderColorRequest
import dev.jordond.filmstrip.media3.internal.encoder.MeasuredEncoderColor
import dev.jordond.filmstrip.media3.internal.encoder.SizeTier
import dev.jordond.filmstrip.media3.internal.encoder.WrittenColor
import dev.jordond.filmstrip.media3.internal.encoder.measureOnDevice
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * What [MeasuredEncoderColor] measures Android's own software encoders writing when asked for SDR BT.709 at limited
 * range, at each [SizeTier].
 *
 * `OMX.google.h264.encoder` on API 30 to 32, and `c2.android.avc.encoder` and `c2.android.hevc.encoder` on API 31
 * and 32, write BT.601 at limited range. Both c2 encoders on API 33 and 36, and the OMX one on 36, write what they are
 * asked for. An encoder the device does not carry, or one not measured on the device's API level, is skipped with
 * what it wrote in the message. Each encoder writes the same at SD and at every other tier it encodes a size in, and
 * a tier it encodes no size in is unclear. Each tier it was measured at reads the same again at that size turned
 * portrait, unless it encodes no portrait size there.
 */
class AndroidEncoderColorMediaTest {
  @Test
  fun theOmxAvcEncoderWritesWhatWasMeasured() = assertMeasured(OMX_AVC, MimeTypes.VIDEO_H264)

  @Test
  fun theC2AvcEncoderWritesWhatWasMeasured() = assertMeasured(C2_AVC, MimeTypes.VIDEO_H264)

  @Test
  fun theC2HevcEncoderWritesWhatWasMeasured() = assertMeasured(C2_HEVC, MimeTypes.VIDEO_H265)

  private fun assertMeasured(
    encoder: String,
    mimeType: String,
  ) {
    val present = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.name == encoder }
    assumeTrue("this device carries no $encoder", present)

    val written =
      SizeTier.entries.associateWith { tier ->
        val request = EncoderColorRequest(encoder, mimeType, tier, ColorInfo.SDR_BT709_LIMITED)
        val started = System.nanoTime()
        MeasuredEncoderColor.Device.written(request).also {
          val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
          Log.i(TAG, "$encoder on API ${Build.VERSION.SDK_INT} at $tier took $millis ms")
        }
      }

    val level = Build.VERSION.SDK_INT
    Log.i(TAG, "$encoder on API $level writes $written")
    val expected =
      MEASURED
        .getValue(encoder)
        .entries
        .firstOrNull { level in it.key }
        ?.value
    assumeTrue("$encoder was not measured on API $level, it wrote $written", expected != null)
    val measured = WrittenColor.Measured(checkNotNull(expected))
    withClue("$encoder on API $level at SD") { written[SizeTier.SD] shouldBe measured }
    written.forEach { (tier, color) ->
      val unclear = WrittenColor.Unclear("it encodes no $tier size")
      withClue("$encoder on API $level at $tier wrote $color") { (color == measured || color == unclear) shouldBe true }
    }

    val portrait =
      written.filterValues { it == measured }.mapValues { (tier, _) ->
        val request = EncoderColorRequest(encoder, mimeType, tier, ColorInfo.SDR_BT709_LIMITED)
        measureOnDevice(request, System.nanoTime() + PORTRAIT_TIMEOUT, portrait = true)
      }
    Log.i(TAG, "$encoder on API $level writes $portrait in portrait")
    withClue("$encoder on API $level at SD in portrait") { portrait[SizeTier.SD] shouldBe measured }
    portrait.forEach { (tier, color) ->
      val unclear = WrittenColor.Unclear("it encodes no portrait $tier size")
      withClue("$encoder on API $level at $tier in portrait wrote $color") {
        (color == measured || color == unclear) shouldBe true
      }
    }
  }

  private companion object {
    const val TAG = "AndroidEncoderColor"
    const val OMX_AVC = "OMX.google.h264.encoder"
    const val C2_AVC = "c2.android.avc.encoder"
    const val C2_HEVC = "c2.android.hevc.encoder"
    val PORTRAIT_TIMEOUT = TimeUnit.SECONDS.toNanos(5)

    val BT601_LIMITED: ColorInfo =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorSpace(C.COLOR_SPACE_BT601)
        .build()

    val C2_LEVELS =
      mapOf(
        31..32 to BT601_LIMITED,
        33..33 to ColorInfo.SDR_BT709_LIMITED,
        36..36 to ColorInfo.SDR_BT709_LIMITED,
      )

    // The color each encoder was measured writing, by the API levels it was measured on.
    val MEASURED =
      mapOf(
        OMX_AVC to mapOf(30..32 to BT601_LIMITED, 36..36 to ColorInfo.SDR_BT709_LIMITED),
        C2_AVC to C2_LEVELS,
        C2_HEVC to C2_LEVELS,
      )
  }
}
