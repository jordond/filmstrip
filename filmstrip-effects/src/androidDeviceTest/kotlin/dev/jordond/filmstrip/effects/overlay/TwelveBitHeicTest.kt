package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicColourPictures
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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

        val reason = assertIs<RefusedPicture>(picture, "matrix $matrix full $full").reason
        assertEquals(DecoderRefusal.HeicTwelveBitNoFrame.message, reason, "matrix $matrix full $full")
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

    assertNull(BitmapFactory.decodeByteArray(file, 0, file.size))
    assertNull(BitmapFactory.decodeByteArray(file, 0, file.size, wide))
  }

  private fun twelveBit(
    matrix: Int,
    full: Boolean,
  ): ByteArray = HeicColourPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, matrix, full))

  private fun assumeHeicDecodes() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val first = ColourPictures.HEICS.first().bytes()
    assumeTrue(HEIC_FIXTURE_UNDECODED, BitmapFactory.decodeByteArray(first, 0, first.size) != null)
  }

  private companion object {
    const val BT709 = 1
    const val BT601 = 6
    const val BT2020 = 9
    const val SRGB_TRANSFER = 13
  }
}
