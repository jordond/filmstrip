package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PictureCropTest {
  @Test
  fun croppedSamplesKeepsTheRowsAndColumnsTheCropNamesOnPackedTexels() {
    val crop = PictureCrop(2, 1, Size(3, 2))
    val expected = IntArray(6) { at -> indexOf(crop.left + at % 3, crop.top + at / 3) }

    croppedSamples(crop, packed(), STORED) shouldBe expected
  }

  @Test
  fun croppedSamplesMovesTheFourSamplesOfATexelTogether() {
    val crop = PictureCrop(1, 2, Size(4, 1))
    val rgba = ShortArray(STORED.width * STORED.height * 4) { (it / 4 * 10 + it % 4).toShort() }
    val expected = ShortArray(16) { (indexOf(1 + it / 4, 2) * 10 + it % 4).toShort() }

    croppedSamples(crop, rgba, STORED) shouldBe expected
  }

  @Test
  fun aCropKeepingTheWholePictureGivesACopy() {
    val argb = packed()
    val whole = PictureCrop(0, 0, STORED)

    whole.keepsAll(STORED).shouldBeTrue()
    croppedSamples(whole, argb, STORED) shouldBe argb
  }

  @Test
  fun aCropReachingPastThePictureIsRefused() {
    val past = PictureCrop(4, 0, Size(3, 4))

    past.liesWithin(STORED).shouldBeFalse()
    PictureCrop(-1, 0, Size(2, 2)).liesWithin(STORED).shouldBeFalse()
    PictureCrop(3, 0, Size(3, 4)).liesWithin(STORED).shouldBeTrue()
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
