package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Apple's text raster check against the peak its HDR draw counts, for a sixteen-bit raster drawn through one band of
 * [StoredBands].
 */
class TextRasterPeakTest {
  @Test
  fun refusesTheTextItsDrawWouldRefuse() {
    assertAgrees(WIDTH, TALL)
    fits(WIDTH, TALL) shouldBe false
  }

  @Test
  fun agreesInTheMiddleOfWhereTheBandTipsIt() {
    assertAgrees(WIDTH, MIDDLE)
    fits(WIDTH, MIDDLE) shouldBe false
  }

  @Test
  fun agreesAtTheLastRowThatFitsAndTheFirstThatDoesNot() {
    assertAgrees(WIDTH, LAST_ROW)
    fits(WIDTH, LAST_ROW) shouldBe true
    assertAgrees(WIDTH, LAST_ROW + 1)
    fits(WIDTH, LAST_ROW + 1) shouldBe false
  }

  @Test
  fun agreesOnASmallTextHeldInOneBand() {
    assertAgrees(SMALL_WIDTH, SMALL_HEIGHT)
    fits(SMALL_WIDTH, SMALL_HEIGHT) shouldBe true
  }

  @Test
  fun aRasterWithNoTexelsHoldsNoBand() {
    drawnBandBytes(0, SMALL_HEIGHT.toLong()) shouldBe 0L
    drawnBandBytes(SMALL_WIDTH.toLong(), 0) shouldBe 0L
  }

  @Test
  fun aRasterTooWideToReadInBandsHasNoBandAndDoesNotFit() {
    val wide = Int.MAX_VALUE / RGBA_CHANNELS + 1L

    drawnBandBytes(wide, 1).shouldBeNull()
    drawnBandBytes(wide - 1, 1).shouldNotBeNull()
    textRasterFits(wide, 1, SIXTEEN_BIT_TEXEL, drawnBandBytes(wide, 1), Long.MAX_VALUE) shouldBe false
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
    val peak = drawnPeakBytes(width.toLong(), height.toLong(), held).shouldNotBeNull()
    withClue("$width by $height peaks at $peak") { fits(width, height) shouldBe (peak <= PICTURE_PEAK_CEILING) }
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
