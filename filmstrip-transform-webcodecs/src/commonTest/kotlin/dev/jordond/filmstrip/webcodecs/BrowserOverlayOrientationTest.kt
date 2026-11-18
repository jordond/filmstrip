package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.OrientedPictures
import dev.jordond.filmstrip.webcodecs.internal.render.cellWindowOf
import dev.jordond.filmstrip.webcodecs.internal.render.textureMatrixOf
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * The texture matrix the overlay pass lays a raster uploaded as stored out by, read the way its shader reads it.
 *
 * The centre of every shown texel is carried through the matrix into the stored raster and the texel it lands in is
 * held to the one [orientedSamples] lays there, for all eight orientations of a picture that reads differently under
 * each, so the middle of every quadrant is read.
 *
 * A cell is read the way the shader reads it too, holding the point half a texel inside the cell before the matrix, and
 * the stored texels a bilinear read of it can blend are held to the ones the cell holds.
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

  // Every fragment of a cell, across and past its edges, reads only texels of that cell whichever way the sheet is
  // turned. The stored texels are numbered so the ones a cell holds are the ones orientedSamples lays inside it.
  @Test
  fun aCellReadsNoTexelBesideItInAnyOrientation() {
    val stored = Size(STORED_WIDTH, STORED_HEIGHT)
    val numbered = IntArray(stored.width * stored.height) { it }
    for (orientation in PictureOrientation.entries) {
      val shown = orientation.orientedSize(stored)
      val laidOut = orientedSamples(orientation, numbered, stored)
      val matrix = textureMatrixOf(orientation, stored)
      val cell = Size(shown.width / GRID, shown.height / GRID)
      for (row in 0 until GRID) {
        for (column in 0 until GRID) {
          val crop = PictureCrop(column * cell.width, row * cell.height, cell)
          val held =
            (crop.top until crop.top + cell.height)
              .flatMap { y ->
                (crop.left until crop.left + cell.width).map { x -> laidOut[y * shown.width + x] }
              }.toSet()
          val window = cellWindowOf(crop, shown)
          for (step in 0..STEPS) {
            for (other in 0..STEPS) {
              val at = floatArrayOf(step / STEPS.toFloat(), other / STEPS.toFloat())
              val read = readWindow(window, at)
              for (texel in footprint(matrix, read, stored)) {
                withClue("$orientation cell ($column, $row) at (${at[0]}, ${at[1]}) read texel $texel") {
                  (texel in held) shouldBe true
                }
              }
            }
          }
        }
      }
    }
  }

  // With no cell the read is the one a plain overlay makes, for every point the shader draws.
  @Test
  fun noCellLeavesTheReadWhereItWas() {
    val window = cellWindowOf(null, Size(STORED_WIDTH, STORED_HEIGHT))
    for (step in 0 until STEPS) {
      val at = floatArrayOf(step / STEPS.toFloat(), (STEPS - step) / STEPS.toFloat())
      readWindow(window, at).toList() shouldBe at.toList()
    }
  }

  // The shader's clamp, which holds a point of the cell half a texel inside it.
  private fun readWindow(
    window: FloatArray,
    at: FloatArray,
  ): FloatArray =
    FloatArray(2) { axis ->
      val low = window[axis] + window[AXIS_HALF + axis]
      val high = window[axis] + window[AXIS_SIZE + axis] - window[AXIS_HALF + axis]
      max(low, min(high, window[axis] + at[axis] * window[AXIS_SIZE + axis]))
    }

  // The stored texels a bilinear read at a shown point blends, which are the four around it with a weight above zero.
  private fun footprint(
    matrix: FloatArray,
    shown: FloatArray,
    stored: Size,
  ): List<Int> {
    val u = (matrix[0] * shown[0] + matrix[3] * shown[1] + matrix[6]) * stored.width - HALF
    val v = (matrix[1] * shown[0] + matrix[4] * shown[1] + matrix[7]) * stored.height - HALF
    val columns = floor(u + EPSILON).toInt()..floor(u - EPSILON).toInt() + 1
    val rows = floor(v + EPSILON).toInt()..floor(v - EPSILON).toInt() + 1
    return rows.flatMap { y ->
      columns.map { x -> y.coerceIn(0, stored.height - 1) * stored.width + x.coerceIn(0, stored.width - 1) }
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
    const val STORED_WIDTH = 24
    const val STORED_HEIGHT = 36
    const val GRID = 3
    const val STEPS = 97
    const val EPSILON = 1e-4f
    const val AXIS_SIZE = 2
    const val AXIS_HALF = 4
  }
}
