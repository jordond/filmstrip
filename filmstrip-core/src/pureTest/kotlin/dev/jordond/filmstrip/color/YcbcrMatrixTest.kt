package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.abs
import kotlin.test.Test

/**
 * How a linear YCbCr decode applies its rows, checked by hand and against [ycbcrToRgb] on samples in the middle of the
 * range, where a matrix applied in the wrong order or with the wrong offsets parts from it.
 */
class YcbcrMatrixTest {
  @Test
  fun `each channel takes its own row of coefficients and its own offset`() {
    val matrix =
      YcbcrMatrix(
        coefficients = doubleArrayOf(1.0, 2.0, 3.0, 0.5, -1.0, 0.25, -2.0, 0.0, 4.0),
        offsets = doubleArrayOf(0.1, -0.2, 0.3),
      )

    val rgb = matrix.rgb(0.4, 0.5, 0.25)

    assertNear(1.0 * 0.4 + 2.0 * 0.5 + 3.0 * 0.25 + 0.1, rgb[0])
    assertNear(0.5 * 0.4 - 1.0 * 0.5 + 0.25 * 0.25 - 0.2, rgb[1])
    assertNear(-2.0 * 0.4 + 4.0 * 0.25 + 0.3, rgb[2])
  }

  @Test
  fun `the matrix rgbMatrix gives decodes a mid sample where ycbcrToRgb does`() {
    for (reading in listOf(ycbcrReading(1, 1, 1), ycbcrReading(9, 9, 16), ycbcrReading(0, 1, 13))) {
      for ((bitDepth, fullRange) in listOf(8 to false, 10 to true, 12 to false)) {
        val matrix = withClue("$reading") { reading.rgbMatrix(bitDepth, fullRange).shouldNotBeNull() }
        val (y, cb, cr) = MID_SAMPLE.map { it shl (bitDepth - 8) }

        val expected = ycbcrToRgb(reading, y, cb, cr, bitDepth, fullRange)
        val decoded = matrix.rgb(y.toDouble(), cb.toDouble(), cr.toDouble())

        for (channel in 0 until 3) assertNear(expected[channel], decoded[channel], "$reading $bitDepth $channel")
        withClue("$reading $bitDepth landed at the ends") { decoded.all { it > 0.05 && it < 0.95 }.shouldBeTrue() }
      }
    }
  }

  // A limited-range sample under the foot reads below zero, which ycbcrToRgb clamps and the matrix does not.
  @Test
  fun `a sample past the range is decoded past zero to one without a clamp`() {
    val reading = ycbcrReading(1, 1, 1)
    val matrix = reading.rgbMatrix(8, false).shouldNotBeNull()

    val below = matrix.rgb(4.0, 128.0, 128.0)

    withClue("${below.toList()} was clamped") { below.all { it < 0.0 }.shouldBeTrue() }
    ycbcrToRgb(reading, 4, 128, 128, 8, false).all { it == 0.0 }.shouldBeTrue()
  }

  @Test
  fun `a reading whose decode is not linear has no matrix`() {
    ycbcrReading(10, 9, 14).rgbMatrix(10, false).shouldBeNull()
  }

  private fun assertNear(
    expected: Double,
    actual: Double,
    message: String = "",
  ) {
    withClue("$message expected $expected but decoded $actual") { (abs(expected - actual) <= 1e-9).shouldBeTrue() }
  }

  private companion object {
    // A mid grey nudged off neutral, so every row of the matrix and every offset weighs in.
    val MID_SAMPLE = listOf(120, 110, 150)
  }
}
