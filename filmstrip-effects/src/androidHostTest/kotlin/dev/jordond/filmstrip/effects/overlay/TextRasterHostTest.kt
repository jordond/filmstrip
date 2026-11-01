package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What Android's text raster counts against its ceiling, off the device.
 *
 * Every array the raster holds lives on the Java heap, so the ceiling is the smaller of the picture ceiling and half
 * that heap. The heaps here are literals, since they pin Android's own limit. Preparing a raster that runs out of
 * memory is refused by name. The pixels the raster keeps are read on a device, in `TextPlateRasterTest`.
 */
class TextRasterHostTest {
  @Test
  fun aHeapUnderTwiceThePictureCeilingGivesHalfOfItself() {
    assertEquals(32L shl 20, textCeiling(64L shl 20))
    assertEquals(128L shl 20, textCeiling(256L shl 20))
    assertEquals(512L shl 20, textCeiling(1L shl 30))
  }

  // Half of a 2 GiB heap is the picture ceiling, where the two meet.
  @Test
  fun aHeapOfTwiceThePictureCeilingMeetsIt() {
    assertEquals(1L shl 30, textCeiling(2L shl 30))
    assertEquals(PICTURE_PEAK_CEILING, textCeiling(2L shl 30))
  }

  @Test
  fun aHeapPastTwiceThePictureCeilingLeavesThePictureCeiling() {
    assertEquals(PICTURE_PEAK_CEILING, textCeiling((2L shl 30) + 1))
    assertEquals(PICTURE_PEAK_CEILING, textCeiling(3L shl 30))
    assertEquals(PICTURE_PEAK_CEILING, textCeiling(Long.MAX_VALUE))
  }

  @Test
  fun aRasterFitsUnderTheHeapAtItsEdgeAndNotOneTexelPast() {
    val ceiling = textCeiling(HEAP)
    val edge = edgeTexels(ceiling)

    assertTrue(fits(edge, ceiling), "$edge texels against a $ceiling byte heap")
    assertFalse(fits(edge + 1, ceiling), "${edge + 1} texels against a $ceiling byte heap")
  }

  // The heap refuses a raster the picture ceiling alone would hold.
  @Test
  fun theHeapRefusesARasterThePictureCeilingHolds() {
    val past = edgeTexels(textCeiling(HEAP)) + 1

    assertTrue(fits(past, PICTURE_PEAK_CEILING), "the picture ceiling should hold $past texels")
    assertFalse(fits(past, textCeiling(HEAP)))
  }

  @Test
  fun aBandPastALongIsNotCountedAndTheRasterIsRefused() {
    assertNull(textBandBytes(SIDE, SIDE))
    assertFalse(
      textRasterFits(
        SIDE,
        SIDE,
        TEXT_TEXEL_BYTES,
        textBandBytes(SIDE, SIDE),
      ),
    )
  }

  @Test
  fun anEmptyRasterHoldsNoBand() {
    assertEquals(0L, textBandBytes(0, HEIGHT))
  }

  // Twice the texels hold twice the band, at a size in the middle of the range rather than at either end of it.
  @Test
  fun theBandGrowsWithTheTexels() {
    val one = textBandBytes(WIDTH, HEIGHT)

    assertEquals(one?.times(2), textBandBytes(WIDTH, 2 * HEIGHT))
  }

  @Test
  fun preparingARasterAndroidCannotAllocateIsRefusedByName() {
    val samples = TextSamples(Size(WIDTH.toInt(), HEIGHT.toInt()), ShortArray(0))

    val prepared = samples.rasterFor(transfer = null) { _, _, _, _ -> throw OutOfMemoryError("the heap is spent") }

    assertEquals(textAllocationFailed(WIDTH.toInt(), HEIGHT.toInt()), assertIs<TextRaster.Refused>(prepared).reason)
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
