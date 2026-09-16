package dev.jordond.filmstrip.media3.internal

import android.os.SystemClock
import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.VideoCodec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The message an [ExportException] leaves on the [ExportError] it is classified as.
 *
 * media3 gives most failures a fixed message naming the stage, like "Muxer error", and puts what happened in the
 * cause. These build the exceptions with media3's own factories and check that the cause reaches the caller.
 *
 * An [ExportException] stamps itself with `SystemClock.elapsedRealtime()`, which the host's android.jar does not
 * implement, so the clock is stubbed out.
 */
class Media3ErrorsTest {
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
  fun `a muxer timeout keeps the abort media3 gave as its cause`() {
    val abort = IllegalStateException("$ABORT DebugTrace: VideoEncoder: no frames")

    val error = muxerException(abort, ExportException.ERROR_CODE_MUXING_TIMEOUT).toExportError(VideoCodec.H264)

    val underlying = error.shouldBeInstanceOf<ExportError.Underlying>()
    underlying.platformCode shouldBe ExportException.ERROR_CODE_MUXING_TIMEOUT
    underlying.message shouldBe "Muxer error: $ABORT DebugTrace: VideoEncoder: no frames"
  }

  @Test
  fun `a muxer failure is still classified by its code`() {
    val error =
      muxerException(IOException("No space left on device"), ExportException.ERROR_CODE_MUXING_FAILED)
        .toExportError(VideoCodec.H264)

    error.shouldBeInstanceOf<ExportError.SinkUnwritable>().message shouldBe "Muxer error: No space left on device"
  }

  @Test
  fun `every cause down the chain is named`() {
    val chain = IOException("read failed", IllegalStateException("codec released"))

    val error =
      ExportException
        .createForAssetLoader(chain, ExportException.ERROR_CODE_IO_UNSPECIFIED)
        .toExportError(VideoCodec.H264)

    error.shouldBeInstanceOf<ExportError.SourceUnreadable>().message shouldBe
      "Asset loader error: read failed: codec released"
  }

  // IOException(Throwable) takes the cause's toString() as its own message.
  @Test
  fun `a cause the wrapper above it already quotes is named once`() {
    val missing = FileNotFoundException("/sdcard/clip.mp4: open failed: ENOENT")

    val error =
      ExportException
        .createForAssetLoader(IOException(missing), ExportException.ERROR_CODE_IO_FILE_NOT_FOUND)
        .toExportError(VideoCodec.H264)

    error.message shouldBe "Asset loader error: java.io.FileNotFoundException: /sdcard/clip.mp4: open failed: ENOENT"
  }

  @Test
  fun `a cause repeating the message above it is named once`() {
    val cause = IllegalStateException("surface lost", IllegalStateException("surface lost"))

    val error = ExportException.createForUnexpected(cause).toExportError(VideoCodec.H264)

    error.message shouldBe "Unexpected runtime error: surface lost"
  }

  @Test
  fun `a cause with no message is skipped`() {
    val cause = IllegalStateException(null, IllegalStateException(" ", IllegalStateException("encoder stalled")))

    val error = ExportException.createForUnexpected(cause).toExportError(VideoCodec.H264)

    error.message shouldBe "Unexpected runtime error: encoder stalled"
  }

  @Test
  fun `a cause chain that loops back on itself is walked once`() {
    val first = IllegalStateException("first")
    val second = IllegalStateException("second")
    first.initCause(second)
    second.initCause(first)

    val error = ExportException.createForUnexpected(first).toExportError(VideoCodec.H264)

    error.message shouldBe "Unexpected runtime error: first: second"
  }

  @Test
  fun `a failure with no message anywhere names its code`() {
    val exception = exportException(null, IllegalStateException(), ExportException.ERROR_CODE_MUXING_TIMEOUT)

    val error = exception.toExportError(VideoCodec.H264)

    val underlying = error.shouldBeInstanceOf<ExportError.Underlying>()
    underlying.platformCode shouldBe ExportException.ERROR_CODE_MUXING_TIMEOUT
    underlying.message shouldBe "media3 reported error code ${ExportException.ERROR_CODE_MUXING_TIMEOUT}."
  }

  // The watchdog's abort goes through a factory media3 keeps to its own package.
  private fun muxerException(
    cause: Throwable,
    errorCode: Int,
  ): ExportException =
    ExportException::class.java
      .getDeclaredMethod("createForMuxer", Throwable::class.java, Int::class.javaPrimitiveType)
      .apply { isAccessible = true }
      .invoke(null, cause, errorCode) as ExportException

  // Every factory media3 exposes sets a message, so only the private constructor can leave it out.
  private fun exportException(
    message: String?,
    cause: Throwable?,
    errorCode: Int,
  ): ExportException =
    ExportException::class.java
      .getDeclaredConstructor(String::class.java, Throwable::class.java, Int::class.javaPrimitiveType)
      .apply { isAccessible = true }
      .newInstance(message, cause, errorCode)

  private companion object {
    const val ABORT = "Abort: no output sample written in the last 10000 milliseconds."
  }
}
