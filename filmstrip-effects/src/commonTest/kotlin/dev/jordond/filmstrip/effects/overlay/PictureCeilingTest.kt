package dev.jordond.filmstrip.effects.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a text raster stops fitting under the picture ceiling, counted with the band held on the way and the floats an
 * HDR frame draws it from.
 */
class PictureCeilingTest {
  @Test
  fun aSixteenBitRasterFitsAtTheCeilingAndNotOneTexelPast() {
    val edge = edgeTexels(SIXTEEN_BIT_TEXEL)

    assertTrue(textRasterFits(edge, 1, SIXTEEN_BIT_TEXEL, band = 0L))
    assertFalse(textRasterFits(edge + 1, 1, SIXTEEN_BIT_TEXEL, band = 0L))
  }

  @Test
  fun anEightBitRasterFitsAtTheCeilingAndNotOneRowPast() {
    val width = 4096L
    val rows = edgeTexels(EIGHT_BIT_TEXEL) / width

    assertTrue(textRasterFits(width, rows, EIGHT_BIT_TEXEL, band = 0L))
    assertFalse(textRasterFits(width, rows + 1, EIGHT_BIT_TEXEL, band = 0L))
  }

  @Test
  fun countsTheHdrFloatCopyBesideTheRaster() {
    val past = edgeTexels(SIXTEEN_BIT_TEXEL) + 1

    assertTrue(past * SIXTEEN_BIT_TEXEL <= PICTURE_PEAK_CEILING, "the raster alone should fit")
    assertFalse(textRasterFits(past, 1, SIXTEEN_BIT_TEXEL, band = 0L))
  }

  @Test
  fun countsTheBandBesideTheRasterAndItsFloats() {
    val texels = edgeTexels(SIXTEEN_BIT_TEXEL) / 2
    val room = PICTURE_PEAK_CEILING - texels * (SIXTEEN_BIT_TEXEL + FLOAT_TEXEL_BYTES)

    assertTrue(textRasterFits(texels, 1, SIXTEEN_BIT_TEXEL, band = room))
    assertFalse(textRasterFits(texels, 1, SIXTEEN_BIT_TEXEL, band = room + 1))
  }

  @Test
  fun aBandThatCouldNotBeCountedIsRefused() {
    assertFalse(textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, band = null))
    assertFalse(textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, band = -1L))
  }

  @Test
  fun readsTheCeilingItIsGiven() {
    val peak = WIDTH * (SIXTEEN_BIT_TEXEL + FLOAT_TEXEL_BYTES) + BAND

    assertTrue(textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, BAND, ceiling = peak))
    assertFalse(textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, BAND, ceiling = peak - 1))
  }

  @Test
  fun aRasterWithNoTexelsFits() {
    assertTrue(textRasterFits(0L, 0L, SIXTEEN_BIT_TEXEL, band = null))
  }

  @Test
  fun aRasterWithOneEmptySideFits() {
    assertTrue(textRasterFits(0L, HEIGHT, SIXTEEN_BIT_TEXEL, band = null))
    assertTrue(textRasterFits(WIDTH, 0L, SIXTEEN_BIT_TEXEL, band = null))
  }

  @Test
  fun aNegativeSideIsRefused() {
    assertFalse(textRasterFits(-1L, HEIGHT, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
    assertFalse(textRasterFits(WIDTH, -1L, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
    assertFalse(textRasterFits(-1L, 0L, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
  }

  @Test
  fun aSizeWhoseBytesPassALongIsRefused() {
    val side = Int.MAX_VALUE.toLong()

    assertFalse(textRasterFits(side, side, Int.MAX_VALUE, band = 0L, ceiling = Long.MAX_VALUE))
  }

  @Test
  fun aSidePastAnIntIsRefusedWhateverTheCeiling() {
    val side = Int.MAX_VALUE.toLong()

    assertTrue(textRasterFits(side, 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
    assertFalse(textRasterFits(side + 1, 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
    assertFalse(textRasterFits(1, side + 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
  }

  @Test
  fun aPlateSideIsTheBlockAndBothPaddingsWithoutOverflow() {
    assertEquals(BLOCK + 2L * PADDING, plateSide(BLOCK.toLong(), PADDING))
    assertEquals(3L * Int.MAX_VALUE, plateSide(Int.MAX_VALUE.toLong(), Int.MAX_VALUE))
    assertEquals(BLOCK.toLong(), plateSide(BLOCK.toLong(), 0))
  }

  @Test
  fun aPlateSideHoldsAtTheLargestLongRatherThanWrapping() {
    val last = Long.MAX_VALUE - 2L * PADDING
    assertEquals(Long.MAX_VALUE, plateSide(last, PADDING))
    assertEquals(Long.MAX_VALUE, plateSide(last + 1, PADDING))
    assertEquals(Long.MAX_VALUE, plateSide(Long.MAX_VALUE, Int.MAX_VALUE))
    assertEquals(last - 1 + 2L * PADDING, plateSide(last - 1, PADDING))
  }

  @Test
  fun aPlateSidePastAnIntIsRefused() {
    val side = plateSide(Int.MAX_VALUE.toLong(), Int.MAX_VALUE)

    assertFalse(textRasterFits(side, side, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
    assertFalse(textRasterFits(side, 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE))
  }

  @Test
  fun thePeakIsTheSumOfItsParts() {
    assertEquals(BAND + 7 + 9, picturePeakBytes(held = 7, band = BAND, made = 9))
    assertNull(picturePeakBytes(held = Long.MAX_VALUE, band = 1, made = 0))
    assertNull(picturePeakBytes(held = 1, band = null, made = 1))
  }

  @Test
  fun theRefusalNamesTheSizeAndTheCeiling() {
    val reason = textTooLargeToDraw(WIDTH, HEIGHT, SIXTEEN_BIT_TEXEL)

    assertTrue("$WIDTH by $HEIGHT" in reason, reason)
    assertTrue("$PICTURE_PEAK_CEILING bytes" in reason, reason)
  }

  @Test
  fun theRefusalNamesTheCeilingItIsGiven() {
    val reason = textTooLargeToDraw(WIDTH, HEIGHT, SIXTEEN_BIT_TEXEL, ceiling = BAND)

    assertTrue("$BAND bytes" in reason, reason)
  }

  @Test
  fun anAllocationFailureNamesTheSizeAndNoCeiling() {
    val reason = textAllocationFailed(BLOCK, PADDING)

    assertTrue("$BLOCK by $PADDING" in reason, reason)
    assertFalse("bytes" in reason, reason)
  }

  private fun edgeTexels(bytesPerTexel: Int): Long = PICTURE_PEAK_CEILING / (bytesPerTexel + FLOAT_TEXEL_BYTES)

  private companion object {
    const val SIXTEEN_BIT_TEXEL = 8
    const val EIGHT_BIT_TEXEL = 4
    const val WIDTH = 70_000L
    const val HEIGHT = 20_000L
    const val BAND = 50_319_360L
    const val BLOCK = 1_000
    const val PADDING = 300
  }
}
