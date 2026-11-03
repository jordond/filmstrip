package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.ycbcrReading
import kotlin.math.roundToInt

/**
 * The 8-bit red, green and blue codes a YCbCr sample of [bitDepth] bits decodes to under the ITU-T H.273 matrix code
 * point [matrix] beside color primaries [primaries] and transfer characteristics [transfer], each rounded to the
 * nearest code.
 *
 * The decode is core's, [ycbcrReading] of the three code points through its evaluator at [bitDepth] and [fullRange], so
 * the primaries and transfer only change the answer for the matrices that read them, 10, 12 and 13.
 *
 * @throws IllegalArgumentException for a matrix core refuses, or a [bitDepth] outside 8 to 16.
 */
@InternalFilmstripApi
public fun ycbcrToRgb(
  y: Int,
  cb: Int,
  cr: Int,
  matrix: Int,
  primaries: Int,
  transfer: Int,
  fullRange: Boolean,
  bitDepth: Int = 8,
): IntArray {
  val rgb = ycbcrReading(matrix, primaries, transfer).evaluator(bitDepth, fullRange).rgb(y, cb, cr)
  return IntArray(rgb.size) { (rgb[it] * MAX_CODE).roundToInt() }
}

private const val MAX_CODE = 255
