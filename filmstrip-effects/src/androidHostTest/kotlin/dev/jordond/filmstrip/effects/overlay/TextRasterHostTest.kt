package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * What Android's text raster counts against its ceiling, off the device.
 *
 * Every array the raster holds lives on the Java heap, so the ceiling is the smaller of the picture ceiling and half
 * that heap. The heaps here are literals, since they pin Android's own limit. Preparing a raster that runs out of
 * memory is refused by name. The pixels the raster keeps are read on a device, in `TextPlateRasterMediaTest`.
 */
class TextRasterHostTest {
  @Test
  fun aHeapUnderTwiceThePictureCeilingGivesHalfOfItself() {
    textCeiling(64L shl 20) shouldBe (32L shl 20)
    textCeiling(256L shl 20) shouldBe (128L shl 20)
    textCeiling(1L shl 30) shouldBe (512L shl 20)
  }

  // Half of a 2 GiB heap is the picture ceiling, where the two meet.
  @Test
  fun aHeapOfTwiceThePictureCeilingMeetsIt() {
    textCeiling(2L shl 30) shouldBe (1L shl 30)
    textCeiling(2L shl 30) shouldBe PICTURE_PEAK_CEILING
  }

  @Test
  fun aHeapPastTwiceThePictureCeilingLeavesThePictureCeiling() {
    textCeiling((2L shl 30) + 1) shouldBe PICTURE_PEAK_CEILING
    textCeiling(3L shl 30) shouldBe PICTURE_PEAK_CEILING
    textCeiling(Long.MAX_VALUE) shouldBe PICTURE_PEAK_CEILING
  }

  @Test
  fun aRasterFitsUnderTheHeapAtItsEdgeAndNotOneTexelPast() {
    val ceiling = textCeiling(HEAP)
    val edge = edgeTexels(ceiling)

    withClue("$edge texels against a $ceiling byte heap") { fits(edge, ceiling) shouldBe true }
    withClue("${edge + 1} texels against a $ceiling byte heap") { fits(edge + 1, ceiling) shouldBe false }
  }

  // The heap refuses a raster the picture ceiling alone would hold.
  @Test
  fun theHeapRefusesARasterThePictureCeilingHolds() {
    val past = edgeTexels(textCeiling(HEAP)) + 1

    withClue("the picture ceiling should hold $past texels") { fits(past, PICTURE_PEAK_CEILING) shouldBe true }
    fits(past, textCeiling(HEAP)) shouldBe false
  }

  @Test
  fun aBandPastALongIsNotCountedAndTheRasterIsRefused() {
    textBandBytes(SIDE, SIDE).shouldBeNull()
    textRasterFits(
      SIDE,
      SIDE,
      TEXT_TEXEL_BYTES,
      textBandBytes(SIDE, SIDE),
    ) shouldBe false
  }

  @Test
  fun anEmptyRasterHoldsNoBand() {
    textBandBytes(0, HEIGHT) shouldBe 0L
  }

  // Twice the texels hold twice the band, at a size in the middle of the range rather than at either end of it.
  @Test
  fun theBandGrowsWithTheTexels() {
    val one = textBandBytes(WIDTH, HEIGHT)

    textBandBytes(WIDTH, 2 * HEIGHT) shouldBe one?.times(2)
  }

  @Test
  fun preparingARasterAndroidCannotAllocateIsRefusedByName() {
    val samples = TextSamples(Size(WIDTH.toInt(), HEIGHT.toInt()), ShortArray(0))

    val prepared = samples.rasterFor(transfer = null) { _, _, _, _ -> throw OutOfMemoryError("the heap is spent") }

    prepared.shouldBeInstanceOf<TextRaster.Refused>().reason shouldBe
      textAllocationFailed(WIDTH.toInt(), HEIGHT.toInt())
  }

  // Only running out of memory is a refusal, so any other failure on the way is a fault that reaches the caller.
  @Test
  fun anyOtherFailurePreparingARasterIsThrown() {
    val samples = TextSamples(Size(WIDTH.toInt(), HEIGHT.toInt()), ShortArray(0))

    assertFailsWith<IllegalStateException> {
      samples.rasterFor(transfer = null) { _, _, _, _ -> error("not an allocation") }
    }
  }

  private fun fits(
    texels: Long,
    ceiling: Long,
  ): Boolean = textRasterFits(texels, 1, TEXT_TEXEL_BYTES, textBandBytes(texels, 1), ceiling)

  // The most texels whose samples, band and floats fit in ceiling bytes.
  private fun edgeTexels(ceiling: Long): Long {
    val perTexel = TEXT_TEXEL_BYTES + FLOAT_TEXEL_BYTES + (textBandBytes(1, 1) ?: error("one texel has no band"))
    return ceiling / perTexel
  }

  private companion object {
    // A 256 MiB heap, the growth limit a mid-range phone gives an app, so a 128 MiB ceiling.
    const val HEAP = 256L shl 20
    const val SIDE = Int.MAX_VALUE.toLong()
    const val WIDTH = 640L
    const val HEIGHT = 120L
  }
}
