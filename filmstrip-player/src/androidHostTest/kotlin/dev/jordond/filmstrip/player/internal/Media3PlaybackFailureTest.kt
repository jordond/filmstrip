package dev.jordond.filmstrip.player.internal

import android.os.SystemClock
import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
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
 * What a media3 playback failure says about itself on the failure event.
 *
 * A [PlaybackException] stamps itself with `SystemClock.elapsedRealtime()`, which the host's android.jar does not
 * implement, so the clock is stubbed out. A renderer failure builds its message through `TextUtils`, stubbed the same
 * way.
 */
class Media3PlaybackFailureTest {
  @BeforeTest
  fun stubClock() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
    mockkStatic(TextUtils::class)
    every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
  }

  @AfterTest
  fun releaseClock() {
    unmockkAll()
  }

  @Test
  fun `a decoder that failed mid stream names itself and the format it was fed`() {
    val queue = IllegalStateException("queueInputBuffer")
    val decoder = MediaCodecDecoderException(queue, codecInfo("c2.goldfish.h264.decoder"))
    val failure = rendererFailure(decoder, AVC, PlaybackException.ERROR_CODE_DECODING_FAILED)

    val platform = failure.platformFailure()

    platform.backend shouldBe MEDIA3_BACKEND
    platform.code shouldBe PlaybackException.ERROR_CODE_DECODING_FAILED
    platform.codeName shouldBe "ERROR_CODE_DECODING_FAILED"
    platform.codecName shouldBe "c2.goldfish.h264.decoder"
    platform.codecRole shouldBe CodecRole.Decoder
    platform.inputMime shouldBe "video/avc"
    platform.inputCodecs shouldBe "avc1.64001F"
    platform.inputSize shouldBe "1920x1080"
    platform.inputFrameRate shouldBe 29.97f
    platform.causes shouldBe causeChainOf(failure)
  }

  @Test
  fun `a decoder that would not start names the codec it tried and the mime it was asked for`() {
    val starting =
      MediaCodecRenderer.DecoderInitializationException(
        AVC,
        IllegalStateException("configure"),
        false,
        codecInfo("c2.android.hevc.decoder"),
      )
    val failure = rendererFailure(starting, AVC, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)

    val platform = failure.platformFailure()

    platform.codecName shouldBe "c2.android.hevc.decoder"
    platform.codecRole shouldBe CodecRole.Decoder
    platform.inputMime shouldBe "video/avc"
  }

  @Test
  fun `a source failure has no codec and no input format`() {
    val failure =
      PlaybackException(
        "Source error",
        FileNotFoundException("/sdcard/DCIM/a.mp4: open failed: ENOENT"),
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      )

    val platform = failure.platformFailure()

    platform.code shouldBe PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
    platform.codeName shouldBe "ERROR_CODE_IO_FILE_NOT_FOUND"
    platform.codecName.shouldBeNull()
    platform.codecRole.shouldBeNull()
    platform.inputMime.shouldBeNull()
    platform.inputSize.shouldBeNull()
    platform.causes shouldBe causeChainOf(failure)
  }

  @Test
  fun `a format with no size or rate leaves both out`() {
    val audio = Format.Builder().setSampleMimeType("audio/mp4a-latm").build()
    val failure = rendererFailure(IllegalStateException("boom"), audio, PlaybackException.ERROR_CODE_DECODING_FAILED)

    val platform = failure.platformFailure()

    platform.inputMime shouldBe "audio/mp4a-latm"
    platform.inputSize.shouldBeNull()
    platform.inputFrameRate.shouldBeNull()
  }

  private fun rendererFailure(
    cause: Throwable,
    format: Format,
    code: Int,
  ): ExoPlaybackException =
    ExoPlaybackException.createForRenderer(cause, "MediaCodecVideoRenderer", 0, format, C.FORMAT_HANDLED, false, code)

  private fun codecInfo(name: String): MediaCodecInfo =
    MediaCodecInfo.newInstance(name, "video/avc", "video/avc", null, false, false, false)

  private companion object {
    val AVC: Format =
      Format
        .Builder()
        .setSampleMimeType("video/avc")
        .setCodecs("avc1.64001F")
        .setWidth(1920)
        .setHeight(1080)
        .setFrameRate(29.97f)
        .build()
  }
}
