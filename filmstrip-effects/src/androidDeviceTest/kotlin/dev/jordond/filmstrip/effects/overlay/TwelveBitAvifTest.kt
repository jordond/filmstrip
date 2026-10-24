package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ycbcrToRgb
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's AVIF decoder handed a twelve-bit AVIF the way an overlay hands it over.
 *
 * From API 31 to 35 [read] refuses it by name before the decoder sees it. From API 36 it decodes into eight bits a
 * sample, lands where [decoderLanding] says for an eight-bit picture, and reports the limits that names followed by
 * the cut to eight bits, unless core refuses it or it lands past the bound. Android's own decoder is pinned on each
 * band of API levels, so a platform that starts drawing the picture, or drawing it deeper, shows here.
 *
 * Skipped by name before API 31, which has no AVIF decoder.
 */
class TwelveBitAvifTest {
  @Test
  fun belowApi36ATwelveBitAvifIsRefusedByName() {
    assumeAvifDecodes()
    assumeTrue("Android's AVIF decoder draws a twelve-bit AVIF from API 36", Build.VERSION.SDK_INT < TWELVE_BIT_API)

    for (matrix in MATRICES) {
      val picture = ImageSource.Bytes(twelveBit(matrix, true)).read(context = null)

      assertEquals(DecoderRefusal.AvifTwelveBit.message, assertIs<RefusedPicture>(picture).reason, "matrix $matrix")
    }
  }

  // Android's own AVIF decoder on API 31, 33 and 34, pinned so a platform that starts handing back a frame shows here.
  @Test
  fun onApi31To34TheDecoderHandsBackNoFrame() {
    assumeAvifDecodes()
    assumeTrue("This test pins API 31 to 34's AVIF decoder", Build.VERSION.SDK_INT < WRONG_COLOUR_API)
    val file = twelveBit(BT709, true)

    assertNull(BitmapFactory.decodeByteArray(file, 0, file.size))
  }

  // Android's own AVIF decoder on API 35, pinned so a platform that starts drawing the picture right shows here.
  @Test
  fun onApi35TheDecoderHandsBackColoursNoMatrixGives() {
    assumeTrue("This test pins API 35's AVIF decoder", Build.VERSION.SDK_INT == WRONG_COLOUR_API)

    for (matrix in listOf(BT709, BT601, BT2020)) {
      for (full in listOf(true, false)) {
        val file = twelveBit(matrix, full)
        val decoded = assertNotNull(BitmapFactory.decodeByteArray(file, 0, file.size), "matrix $matrix full $full")
        val codes = decoded.centreCodes()

        assertTrue(LANDINGS.none { (m, f) -> codes.near(want(m, f)) }, "matrix $matrix full $full decoded $codes")
      }
    }
  }

  @Test
  fun fromApi36ATwelveBitAvifLandsAsAnEightBitOneAndReportsTheCut() {
    assumeTrue("Android's AVIF decoder draws a twelve-bit AVIF from API 36", Build.VERSION.SDK_INT >= TWELVE_BIT_API)
    val misses =
      MATRICES.flatMap { matrix ->
        listOf(true, false).mapNotNull { full ->
          val file = twelveBit(matrix, full)
          miss("matrix $matrix full $full", file, file.ownLanding())
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Android's own AVIF decoder on API 36, pinned so a platform that starts keeping twelve bits shows here.
  @Test
  fun onApi36TheDecoderHandsBackNoHalfFloatFrame() {
    assumeTrue("This test pins API 36's AVIF decoder", Build.VERSION.SDK_INT == TWELVE_BIT_API)
    val file = twelveBit(BT709, true)
    val options =
      BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGBA_F16
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
      }

    assertNull(BitmapFactory.decodeByteArray(file, 0, file.size, options))
  }

  // How [file] read through read misses landing where [landing] says at eight bits, with the limits it names and the
  // cut to eight bits, or null. One core refuses, which has no landing, or one landing past the bound misses unless it
  // is refused for it.
  private fun miss(
    what: String,
    file: ByteArray,
    landing: DecoderLanding?,
  ): String? {
    if (landing.refused) return file.refusalMiss(what, landing)
    checkNotNull(landing)
    val picture = ImageSource.Bytes(file).read(context = null)
    if (picture !is DecodedPicture) return "$what came to $picture"
    val limits = landing.limits + DecoderLimit.AvifTwelveBitCut
    if (picture.limits != limits) return "$what reported ${picture.limits} where it should report $limits"
    if (picture.bitmap.config != Bitmap.Config.ARGB_8888) return "$what decoded into ${picture.bitmap.config}"
    val codes = picture.bitmap.centreCodes()
    val want = want(landing.matrix, landing.fullRange)
    return if (codes.near(want)) null else "$what decoded $codes where its YCbCr gives ${want.toList()}"
  }

  private fun twelveBit(
    matrix: Int,
    full: Boolean,
  ): ByteArray = AvifColourPictures.twelveBit(CicpTag(BT709, SRGB_TRANSFER, matrix, full)).bytes()

  private fun want(
    matrix: Int,
    full: Boolean,
  ): IntArray {
    val (y, cb, cr) = AvifColourPictures.TWELVE_BIT_YCBCR
    return ycbcrToRgb(y, cb, cr, matrix, BT709, SRGB_TRANSFER, full, bitDepth = TWELVE_BITS)
  }

  private fun List<Float>.near(want: IntArray): Boolean = indices.all { abs(this[it] - want[it]) <= ONE_CODE }

  private fun Bitmap.centreCodes(): List<Float> {
    val read = getColor(width / 2, height / 2)
    return (0 until RGB).map { read.getComponent(it) * MAX_CODE }
  }

  private fun assumeAvifDecodes() {
    assumeTrue("Android decodes AVIF from API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
  }

  private companion object {
    const val BT709 = 1
    const val FCC = 4
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val YCGCO = 8
    const val BT2020 = 9
    const val BT2020_CONSTANT = 10
    const val SRGB_TRANSFER = 13
    const val TWELVE_BITS = 12
    const val RGB = 3
    const val MAX_CODE = 255f
    const val ONE_CODE = 1f
    const val WRONG_COLOUR_API = 35
    const val TWELVE_BIT_API = 36
    val MATRICES = listOf(BT709, FCC, BT601, SMPTE_240, YCGCO, BT2020, BT2020_CONSTANT)
    val LANDINGS = listOf(BT601, BT709, BT2020).flatMap { listOf(it to true, it to false) }
  }
}
