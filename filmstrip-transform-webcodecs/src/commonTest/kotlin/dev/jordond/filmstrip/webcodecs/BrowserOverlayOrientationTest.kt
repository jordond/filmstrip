package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.OrientedPictures
import dev.jordond.filmstrip.webcodecs.internal.render.textureMatrixOf
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.floor
import kotlin.test.Test

/**
 * The texture matrix the overlay pass lays a raster uploaded as stored out by, read the way its shader reads it.
 *
 * The centre of every shown texel is carried through the matrix into the stored raster and the texel it lands in is
 * held to the one [orientedSamples] lays there, for all eight orientations of a picture that reads differently under
 * each, so the middle of every quadrant is read.
 */
class BrowserOverlayOrientationTest {
  @Test
  fun everyOrientationReadsTheTexelCoreLaysOut() {
    val stored = OrientedPictures.SIZE
    val texels = OrientedPictures.samples()
    for (orientation in PictureOrientation.entries) {
      val shown = orientation.orientedSize(stored)
      val expected = orientedSamples(orientation, texels, stored)
      val matrix = textureMatrixOf(orientation, stored)
      for (y in 0 until shown.height) {
        for (x in 0 until shown.width) {
          val read = texels[storedIndex(matrix, (x + HALF) / shown.width, (y + HALF) / shown.height, stored)]
          withClue("$orientation at ($x, $y)") { read shouldBe expected[y * shown.width + x] }
        }
      }
    }
  }

  // The stored texel a point u, v of the shown picture reads, carried through the column-major matrix.
  private fun storedIndex(
    matrix: FloatArray,
    u: Float,
    v: Float,
    stored: Size,
  ): Int {
    val storedU = matrix[0] * u + matrix[3] * v + matrix[6]
    val storedV = matrix[1] * u + matrix[4] * v + matrix[7]
    val column = floor(storedU * stored.width).toInt()
    val row = floor(storedV * stored.height).toInt()
    return row * stored.width + column
  }

  private companion object {
    const val HALF = 0.5f
  }
}
