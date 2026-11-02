package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PictureCropTest {
  @Test
  fun croppedSamplesKeepsTheRowsAndColumnsTheCropNamesOnPackedTexels() {
    val crop = PictureCrop(2, 1, Size(3, 2))
    val expected = IntArray(6) { at -> indexOf(crop.left + at % 3, crop.top + at / 3) }

    assertContentEquals(expected, croppedSamples(crop, packed(), STORED))
  }

  @Test
  fun croppedSamplesMovesTheFourSamplesOfATexelTogether() {
    val crop = PictureCrop(1, 2, Size(4, 1))
    val rgba = ShortArray(STORED.width * STORED.height * 4) { (it / 4 * 10 + it % 4).toShort() }
    val expected = ShortArray(16) { (indexOf(1 + it / 4, 2) * 10 + it % 4).toShort() }

    assertContentEquals(expected, croppedSamples(crop, rgba, STORED))
  }

  @Test
  fun aCropKeepingTheWholePictureGivesACopy() {
    val argb = packed()
    val whole = PictureCrop(0, 0, STORED)

    assertTrue(whole.keepsAll(STORED))
    assertContentEquals(argb, croppedSamples(whole, argb, STORED))
  }

  @Test
  fun aCropReachingPastThePictureIsRefused() {
    val past = PictureCrop(4, 0, Size(3, 4))

    assertFalse(past.liesWithin(STORED))
    assertFalse(PictureCrop(-1, 0, Size(2, 2)).liesWithin(STORED))
    assertTrue(PictureCrop(3, 0, Size(3, 4)).liesWithin(STORED))
    assertFailsWith<IllegalArgumentException> { croppedSamples(past, packed(), STORED) }
  }

  private fun packed(): IntArray = IntArray(STORED.width * STORED.height) { it }

  private fun indexOf(
    x: Int,
    y: Int,
  ): Int = y * STORED.width + x

  private companion object {
    val STORED = Size(6, 4)
  }
}
