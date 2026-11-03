package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * A linear decode from YCbCr samples to red, green and blue, as [rgbMatrix] gives it.
 *
 * Red is `coefficients[0] * y + coefficients[1] * cb + coefficients[2] * cr + offsets[0]`, green the next row and
 * blue the last, each landing in zero to one before any clamp for samples encoded from in-range colors.
 *
 * @property coefficients The nine coefficients row by row, red, green then blue, each row taking luma, blue
 * difference and red difference samples in turn.
 * @property offsets The red, green and blue offsets added after the coefficients.
 */
@InternalFilmstripApi
public class YcbcrMatrix internal constructor(
  public val coefficients: DoubleArray,
  public val offsets: DoubleArray,
) {
  /**
   * The red, green and blue the samples [y], [cb] and [cr] decode to, without clamping.
   */
  public fun rgb(
    y: Double,
    cb: Double,
    cr: Double,
  ): DoubleArray =
    DoubleArray(3) {
      coefficients[it * 3] * y + coefficients[it * 3 + 1] * cb + coefficients[it * 3 + 2] * cr + offsets[it]
    }
}
