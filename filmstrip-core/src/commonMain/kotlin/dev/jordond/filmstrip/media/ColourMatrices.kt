package dev.jordond.filmstrip.media

// Three by three colour matrices, stored row by row, and the chromaticity maths that builds them.

/**
 * The matrix taking linear RGB to XYZ for [chromaticities], with the white's Y at one, or null when they span no
 * colour space.
 *
 * [chromaticities] holds red, green, blue and then the white, each as x then y. A primary may sit on y of zero, the
 * way SMPTE ST 428's XYZ primaries do, but the white may not.
 */
internal fun rgbToXyz(chromaticities: DoubleArray): DoubleArray? {
  if (chromaticities.any { !it.isFinite() } || chromaticities[7] <= 0.0) return null
  val columns = DoubleArray(9)
  for (primary in 0 until 3) {
    val x = chromaticities[primary * 2]
    val y = chromaticities[primary * 2 + 1]
    columns[primary] = x
    columns[3 + primary] = y
    columns[6 + primary] = 1.0 - x - y
  }
  val inverse = invert(columns) ?: return null
  val scale = apply(inverse, xyToXyz(chromaticities[6], chromaticities[7]))
  return DoubleArray(9) { columns[it] * scale[it % 3] }.takeIf { matrix -> matrix.all { it.isFinite() } }
}

/**
 * The matrix adapting XYZ under the white [from] to the white [to] with Bradford, the adaptation the ICC spec and
 * every platform colour management module use.
 */
internal fun bradford(
  from: DoubleArray,
  to: DoubleArray,
): DoubleArray {
  val fromCone = apply(BRADFORD, from)
  val toCone = apply(BRADFORD, to)
  val scaled = DoubleArray(9) { BRADFORD[it] * toCone[it / 3] / fromCone[it / 3] }
  return multiply(BRADFORD_INVERSE, scaled)
}

internal fun xyToXyz(
  x: Double,
  y: Double,
): DoubleArray = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)

internal fun apply(
  matrix: DoubleArray,
  vector: DoubleArray,
): DoubleArray = DoubleArray(3) { row -> (0 until 3).sumOf { matrix[row * 3 + it] * vector[it] } }

internal fun multiply(
  left: DoubleArray,
  right: DoubleArray,
): DoubleArray = DoubleArray(9) { (0 until 3).sumOf { k -> left[it / 3 * 3 + k] * right[k * 3 + it % 3] } }

/**
 * The inverse of [m], or null when [m] is singular.
 */
internal fun invert(m: DoubleArray): DoubleArray? {
  val cofactors =
    doubleArrayOf(
      m[4] * m[8] - m[5] * m[7],
      m[2] * m[7] - m[1] * m[8],
      m[1] * m[5] - m[2] * m[4],
      m[5] * m[6] - m[3] * m[8],
      m[0] * m[8] - m[2] * m[6],
      m[2] * m[3] - m[0] * m[5],
      m[3] * m[7] - m[4] * m[6],
      m[1] * m[6] - m[0] * m[7],
      m[0] * m[4] - m[1] * m[3],
    )
  val determinant = m[0] * cofactors[0] + m[1] * cofactors[3] + m[2] * cofactors[6]
  if (determinant == 0.0 || !determinant.isFinite()) return null
  return DoubleArray(9) { cofactors[it] / determinant }
}

internal val D65_WHITE = xyToXyz(0.3127, 0.3290)

private val BRADFORD =
  doubleArrayOf(
    0.8951,
    0.2664,
    -0.1614,
    -0.7502,
    1.7135,
    0.0367,
    0.0389,
    -0.0685,
    1.0296,
  )

private val BRADFORD_INVERSE = checkNotNull(invert(BRADFORD))
