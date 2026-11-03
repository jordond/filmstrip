package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeicColorPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Android's HEIC decoder handed a twelve-bit HEIC the way an overlay hands it over, and as it stands.
 *
 * [read] refuses it as [DecoderRefusal.HeicTwelveBitNoFrame] whatever its matrix and range. Android's own decoder is
 * pinned handing back no frame for it into eight bits or half floats, so a platform that starts drawing it shows here.
 *
 * Skipped by name on an Android whose HEIC decoder hands back no frame for an eight-bit test still.
 */
class TwelveBitHeicTest {
  @Test
  fun aTwelveBitHeicIsRefusedByName() {
    assumeHeicDecodes()

    for (matrix in listOf(BT709, BT601, BT2020)) {
      for (full in listOf(true, false)) {
        val picture = ImageSource.Bytes(twelveBit(matrix, full)).read(context = null)

        val reason = withClue("matrix $matrix full $full") { picture.shouldBeInstanceOf<RefusedPicture>() }.reason
        withClue("matrix $matrix full $full") { reason shouldBe DecoderRefusal.HeicTwelveBitNoFrame.message }
      }
    }
  }

  // Android's own HEIC decoder, pinned so a platform that starts handing back a frame shows here.
  @Test
  fun theDecoderHandsBackNoFrame() {
    assumeHeicDecodes()
    val file = twelveBit(BT709, true)
    val wide =
      BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGBA_F16
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
      }

    BitmapFactory.decodeByteArray(file, 0, file.size).shouldBeNull()
    BitmapFactory.decodeByteArray(file, 0, file.size, wide).shouldBeNull()
  }

  private fun twelveBit(
    matrix: Int,
    full: Boolean,
  ): ByteArray = HeicColorPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, matrix, full))

  private fun assumeHeicDecodes() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val first = ColorPictures.HEICS.first().bytes()
    assumeTrue(HEIC_FIXTURE_UNDECODED, BitmapFactory.decodeByteArray(first, 0, first.size) != null)
  }

  private companion object {
    const val BT709 = 1
    const val BT601 = 6
    const val BT2020 = 9
    const val SRGB_TRANSFER = 13
  }
}
