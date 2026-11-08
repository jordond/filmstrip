package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import dev.jordond.filmstrip.test.ColorPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Restates an AVIF still through [restated] and reads back what core sees in the bytes it hands over.
 */
class RestatedPictureTest {
  private val bytes = ColorPictures.NCLX_DISPLAY_P3.bytes()
  private val coding = pictureYcbcrCoding(bytes).shouldNotBeNull()

  @Test
  fun aRestateStatesTheMatrixAndRangeBesideThePicturesOwnPrimaries() {
    val landed = landed(restated(bytes, coding, BT709_MATRIX, fullRange = false))

    landed.matrix shouldBe BT709_MATRIX
    landed.fullRange shouldBe false
    landed.primaries shouldBe coding.primaries
  }

  @Test
  fun aRestateWithNoRangeKeepsTheRangeThePictureLandsWith() {
    val landed = landed(restated(bytes, coding, BT709_MATRIX))

    landed.matrix shouldBe BT709_MATRIX
    landed.fullRange shouldBe coding.fullRange
  }

  @Test
  fun primariesPastACodePointAreStatedUnspecified() {
    for (primaries in listOf(-1, CODE_POINTS)) {
      val landed = landed(restated(bytes, coding.copy(primaries = primaries), BT709_MATRIX))

      withClue("primaries $primaries") { landed.primaries shouldBe UNSPECIFIED_PRIMARIES }
    }
  }

  private fun landed(rewrite: RestatedHeif) =
    pictureYcbcrCoding(rewrite.shouldBeInstanceOf<RestatedHeif.Restated>().bytes).shouldNotBeNull()

  private companion object {
    const val BT709_MATRIX = 1
    const val UNSPECIFIED_PRIMARIES = 2
    const val CODE_POINTS = 256
  }
}
