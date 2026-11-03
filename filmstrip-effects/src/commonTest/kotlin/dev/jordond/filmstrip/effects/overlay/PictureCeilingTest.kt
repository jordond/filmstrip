package dev.jordond.filmstrip.effects.overlay

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Where a text raster stops fitting under the picture ceiling, counted with the band held on the way and the floats an
 * HDR frame draws it from.
 */
class PictureCeilingTest {
  @Test
  fun aSixteenBitRasterFitsAtTheCeilingAndNotOneTexelPast() {
    val edge = edgeTexels(SIXTEEN_BIT_TEXEL)

    textRasterFits(edge, 1, SIXTEEN_BIT_TEXEL, band = 0L) shouldBe true
    textRasterFits(edge + 1, 1, SIXTEEN_BIT_TEXEL, band = 0L) shouldBe false
  }

  @Test
  fun anEightBitRasterFitsAtTheCeilingAndNotOneRowPast() {
    val width = 4096L
    val rows = edgeTexels(EIGHT_BIT_TEXEL) / width

    textRasterFits(width, rows, EIGHT_BIT_TEXEL, band = 0L) shouldBe true
    textRasterFits(width, rows + 1, EIGHT_BIT_TEXEL, band = 0L) shouldBe false
  }

  @Test
  fun countsTheHdrFloatCopyBesideTheRaster() {
    val past = edgeTexels(SIXTEEN_BIT_TEXEL) + 1

    withClue("the raster alone should fit") { (past * SIXTEEN_BIT_TEXEL <= PICTURE_PEAK_CEILING) shouldBe true }
    textRasterFits(past, 1, SIXTEEN_BIT_TEXEL, band = 0L) shouldBe false
  }

  @Test
  fun countsTheBandBesideTheRasterAndItsFloats() {
    val texels = edgeTexels(SIXTEEN_BIT_TEXEL) / 2
    val room = PICTURE_PEAK_CEILING - texels * (SIXTEEN_BIT_TEXEL + FLOAT_TEXEL_BYTES)

    textRasterFits(texels, 1, SIXTEEN_BIT_TEXEL, band = room) shouldBe true
    textRasterFits(texels, 1, SIXTEEN_BIT_TEXEL, band = room + 1) shouldBe false
  }

  @Test
  fun aBandThatCouldNotBeCountedIsRefused() {
    textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, band = null) shouldBe false
    textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, band = -1L) shouldBe false
  }

  @Test
  fun readsTheCeilingItIsGiven() {
    val peak = WIDTH * (SIXTEEN_BIT_TEXEL + FLOAT_TEXEL_BYTES) + BAND

    textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, BAND, ceiling = peak) shouldBe true
    textRasterFits(WIDTH, 1, SIXTEEN_BIT_TEXEL, BAND, ceiling = peak - 1) shouldBe false
  }

  @Test
  fun aRasterWithNoTexelsFits() {
    textRasterFits(0L, 0L, SIXTEEN_BIT_TEXEL, band = null) shouldBe true
  }

  @Test
  fun aRasterWithOneEmptySideFits() {
    textRasterFits(0L, HEIGHT, SIXTEEN_BIT_TEXEL, band = null) shouldBe true
    textRasterFits(WIDTH, 0L, SIXTEEN_BIT_TEXEL, band = null) shouldBe true
  }

  @Test
  fun aNegativeSideIsRefused() {
    textRasterFits(-1L, HEIGHT, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
    textRasterFits(WIDTH, -1L, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
    textRasterFits(-1L, 0L, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
  }

  @Test
  fun aSizeWhoseBytesPassALongIsRefused() {
    val side = Int.MAX_VALUE.toLong()

    textRasterFits(side, side, Int.MAX_VALUE, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
  }

  @Test
  fun aSidePastAnIntIsRefusedWhateverTheCeiling() {
    val side = Int.MAX_VALUE.toLong()

    textRasterFits(side, 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe true
    textRasterFits(side + 1, 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
    textRasterFits(1, side + 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
  }

  @Test
  fun aPlateSideIsTheBlockAndBothPaddingsWithoutOverflow() {
    plateSide(BLOCK.toLong(), PADDING) shouldBe BLOCK + 2L * PADDING
    plateSide(Int.MAX_VALUE.toLong(), Int.MAX_VALUE) shouldBe 3L * Int.MAX_VALUE
    plateSide(BLOCK.toLong(), 0) shouldBe BLOCK.toLong()
  }

  @Test
  fun aPlateSideHoldsAtTheLargestLongRatherThanWrapping() {
    val last = Long.MAX_VALUE - 2L * PADDING
    plateSide(last, PADDING) shouldBe Long.MAX_VALUE
    plateSide(last + 1, PADDING) shouldBe Long.MAX_VALUE
    plateSide(Long.MAX_VALUE, Int.MAX_VALUE) shouldBe Long.MAX_VALUE
    plateSide(last - 1, PADDING) shouldBe last - 1 + 2L * PADDING
  }

  @Test
  fun aPlateSidePastAnIntIsRefused() {
    val side = plateSide(Int.MAX_VALUE.toLong(), Int.MAX_VALUE)

    textRasterFits(side, side, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
    textRasterFits(side, 1, SIXTEEN_BIT_TEXEL, band = 0L, ceiling = Long.MAX_VALUE) shouldBe false
  }

  @Test
  fun thePeakIsTheSumOfItsParts() {
    picturePeakBytes(held = 7, band = BAND, made = 9) shouldBe BAND + 7 + 9
    picturePeakBytes(held = Long.MAX_VALUE, band = 1, made = 0).shouldBeNull()
    picturePeakBytes(held = 1, band = null, made = 1).shouldBeNull()
  }

  @Test
  fun theRefusalNamesTheSizeAndTheCeiling() {
    val reason = textTooLargeToDraw(WIDTH, HEIGHT, SIXTEEN_BIT_TEXEL)

    withClue(reason) { ("$WIDTH by $HEIGHT" in reason) shouldBe true }
    withClue(reason) { ("$PICTURE_PEAK_CEILING bytes" in reason) shouldBe true }
  }

  @Test
  fun theRefusalNamesTheCeilingItIsGiven() {
    val reason = textTooLargeToDraw(WIDTH, HEIGHT, SIXTEEN_BIT_TEXEL, ceiling = BAND)

    withClue(reason) { ("$BAND bytes" in reason) shouldBe true }
  }

  @Test
  fun anAllocationFailureNamesTheSizeAndNoCeiling() {
    val reason = textAllocationFailed(BLOCK, PADDING)

    withClue(reason) { ("$BLOCK by $PADDING" in reason) shouldBe true }
    withClue(reason) { ("bytes" in reason) shouldBe false }
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
