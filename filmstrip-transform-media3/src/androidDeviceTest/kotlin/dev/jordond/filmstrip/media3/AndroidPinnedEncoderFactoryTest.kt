package dev.jordond.filmstrip.media3

import android.content.Context
import android.os.Build
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.ExportException
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.media3.internal.encoder.pinnedEncoderFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * What [pinnedEncoderFactory] makes on a device.
 *
 * Pinned to any H.264 or H.265 encoder media3 would pick from, it makes a codec of that name. An alias is skipped,
 * since a made codec names itself by its canonical name. Pinned to a name the device does not list, it fails as media3
 * fails when no encoder it is offered takes the format.
 */
class AndroidPinnedEncoderFactoryTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun aPinnedFactoryMakesTheEncoderItWasPinnedTo() {
    val names =
      listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265).flatMap { mimeType ->
        EncoderSelector.DEFAULT
          .selectEncoderInfos(mimeType)
          .filter { Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !it.isAlias }
          .map { it.name to mimeType }
      }
    assumeTrue("this device lists no H.264 or H.265 encoder", names.isNotEmpty())

    names.forEach { (name, mimeType) ->
      val codec = pinnedEncoderFactory(context, name, bitrate = null).createForVideoEncoding(format(mimeType), null)
      try {
        withClue("the factory pinned to $name") { codec.name shouldBe name }
      } finally {
        codec.release()
      }
    }
  }

  @Test
  fun aPinnedFactoryFailsForAnEncoderTheDeviceDoesNotList() {
    val failure =
      assertFailsWith<ExportException> {
        pinnedEncoderFactory(
          context,
          UNLISTED,
          bitrate = null,
        ).createForVideoEncoding(format(MimeTypes.VIDEO_H264), null)
      }

    withClue(failure.toString()) { failure.errorCode shouldBe ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED }
  }

  private fun format(mimeType: String): Format =
    Format
      .Builder()
      .setSampleMimeType(mimeType)
      .setWidth(WIDTH)
      .setHeight(HEIGHT)
      .setFrameRate(FRAME_RATE)
      .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
      .build()

  private companion object {
    const val UNLISTED = "c2.filmstrip.unlisted.encoder"
    const val WIDTH = 640
    const val HEIGHT = 360
    const val FRAME_RATE = 30f
  }
}
