package dev.jordond.filmstrip.media3.internal

import android.os.SystemClock
import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.diagnostics.CodecRole
import dev.jordond.filmstrip.diagnostics.causeChainOf
import dev.jordond.filmstrip.media3.internal.failure.MEDIA3_BACKEND
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.FileNotFoundException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What a media3 export failure says about itself on the failure event.
 *
 * The exceptions are built with media3's own factories. An [ExportException] stamps itself with
 * `SystemClock.elapsedRealtime()`, which the host's android.jar does not implement, so the clock is stubbed out.
 */
class Media3ExportFailureTest {
  @BeforeTest
  fun stubClock() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
  }

  @AfterTest
  fun releaseClock() {
    unmockkAll()
  }

  @Test
  fun `a decoder failure names the decoder and the format it was configured with`() {
    val failure =
      ExportException.createForCodec(
        IllegalStateException("queueInputBuffer"),
        ExportException.ERROR_CODE_DECODING_FAILED,
        ExportException.CodecInfo(DECODER_FORMAT, true, true, "c2.goldfish.h264.decoder"),
      )

    val platform = failure.platformFailure()

    platform.backend shouldBe MEDIA3_BACKEND
    platform.code shouldBe ExportException.ERROR_CODE_DECODING_FAILED
    platform.codeName shouldBe "ERROR_CODE_DECODING_FAILED"
    platform.codecName shouldBe "c2.goldfish.h264.decoder"
    platform.codecRole shouldBe CodecRole.Decoder
    platform.codecFormat shouldBe DECODER_FORMAT
    platform.causes shouldBe causeChainOf(failure)
  }

  @Test
  fun `an encoder failure names the encoder`() {
    val failure =
      ExportException.createForCodec(
        IllegalArgumentException("configure"),
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.CodecInfo(ENCODER_FORMAT, true, false, "c2.android.hevc.encoder"),
      )

    val platform = failure.platformFailure()

    platform.codeName shouldBe "ERROR_CODE_ENCODER_INIT_FAILED"
    platform.codecName shouldBe "c2.android.hevc.encoder"
    platform.codecRole shouldBe CodecRole.Encoder
    platform.codecFormat shouldBe ENCODER_FORMAT
  }

  @Test
  fun `a missing source carries its code and cause but no codec`() {
    val failure =
      ExportException.createForAssetLoader(
        FileNotFoundException("/sdcard/clip.mp4"),
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
      )

    val platform = failure.platformFailure()

    platform.code shouldBe ExportException.ERROR_CODE_IO_FILE_NOT_FOUND
    platform.codeName shouldBe "ERROR_CODE_IO_FILE_NOT_FOUND"
    platform.codecName.shouldBeNull()
    platform.codecRole.shouldBeNull()
    platform.codecFormat.shouldBeNull()
    platform.causes shouldBe causeChainOf(failure)
  }

  private companion object {
    const val DECODER_FORMAT = "Format(1, null, video/mp4, video/avc, avc1.64001F, -1, null, [1920, 1080, 29.97])"
    const val ENCODER_FORMAT = "{mime=video/hevc, width=1920, height=1080}"
  }
}
