package dev.jordond.filmstrip.effects.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apple's text raster check against the peak its HDR draw counts, for a sixteen-bit raster drawn through one band of
 * [StoredBands].
 */
class TextRasterPeakTest {
  @Test
  fun refusesTheTextItsDrawWouldRefuse() {
    assertAgrees(WIDTH, TALL)
    assertFalse(fits(WIDTH, TALL))
  }

  @Test
  fun agreesInTheMiddleOfWhereTheBandTipsIt() {
    assertAgrees(WIDTH, MIDDLE)
    assertFalse(fits(WIDTH, MIDDLE))
  }

  @Test
  fun agreesAtTheLastRowThatFitsAndTheFirstThatDoesNot() {
    assertAgrees(WIDTH, LAST_ROW)
    assertTrue(fits(WIDTH, LAST_ROW))
    assertAgrees(WIDTH, LAST_ROW + 1)
    assertFalse(fits(WIDTH, LAST_ROW + 1))
  }

  @Test
  fun agreesOnASmallTextHeldInOneBand() {
    assertAgrees(SMALL_WIDTH, SMALL_HEIGHT)
    assertTrue(fits(SMALL_WIDTH, SMALL_HEIGHT))
  }

  @Test
  fun aRasterWithNoTexelsHoldsNoBand() {
    assertEquals(0L, drawnBandBytes(0, SMALL_HEIGHT.toLong()))
    assertEquals(0L, drawnBandBytes(SMALL_WIDTH.toLong(), 0))
  }

  @Test
  fun aRasterTooWideToReadInBandsHasNoBandAndDoesNotFit() {
    val wide = Int.MAX_VALUE / RGBA_CHANNELS + 1L

    assertNull(drawnBandBytes(wide, 1))
    assertNotNull(drawnBandBytes(wide - 1, 1))
    assertFalse(textRasterFits(wide, 1, SIXTEEN_BIT_TEXEL, drawnBandBytes(wide, 1), Long.MAX_VALUE))
  }

  private fun fits(
    width: Int,
    height: Int,
  ): Boolean {
    val wide = width.toLong()
    val tall = height.toLong()
    return textRasterFits(wide, tall, SIXTEEN_BIT_TEXEL, drawnBandBytes(wide, tall))
  }

  private fun assertAgrees(
    width: Int,
    height: Int,
  ) {
    val held = width.toLong() * height * SIXTEEN_BIT_TEXEL
    val peak = assertNotNull(drawnPeakBytes(width.toLong(), height.toLong(), held))
    assertEquals(peak <= PICTURE_PEAK_CEILING, fits(width, height), "$width by $height peaks at $peak")
  }

  private companion object {
    const val SIXTEEN_BIT_TEXEL = 8

    // The channels a band holds a texel, whose count along a row has to fit in an Int.
    const val RGBA_CHANNELS = 4
    const val WIDTH = 1280

    // The raster and its floats alone fit up to 34,952 rows at this width, the band tips everything past 33,314.
    const val LAST_ROW = 33_314
    const val MIDDLE = 34_133
    const val TALL = 33_600
    const val SMALL_WIDTH = 640
    const val SMALL_HEIGHT = 120
  }
}
