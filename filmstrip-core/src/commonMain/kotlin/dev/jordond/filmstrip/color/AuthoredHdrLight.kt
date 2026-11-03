package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

// An authored color, whether a fill or an overlay texel, is sRGB, and an HDR export is neither sRGB
// nor a fixed brightness. The conversion lives here so every backend lands the same color on the
// same light. Every figure is absolute, in cd/m2, and a backend divides by whatever its own pipeline
// normalises against.

/**
 * Converts one sRGB color to linear BT.2020 display light, in cd/m2.
 *
 * Each channel is an encoded sRGB value in the range zero to one. A fully saturated channel lands at
 * [HDR_REFERENCE_WHITE_NITS] rather than at the format's peak, so white reads as paper white next to
 * the picture instead of as a highlight.
 *
 * @return Red, green and blue in that order.
 */
@InternalFilmstripApi
public fun authoredHdrNits(
  red: Float,
  green: Float,
  blue: Float,
): FloatArray {
  // Kotlin/JS keeps Float arithmetic at double precision until it is stored in a FloatArray. Storing the
  // linear channels first rounds them the way the texel table does, so both paths start from one figure.
  // Storing the encoded channels too rounds a level a caller divided out on JS the way the JVM does.
  val nits = floatArrayOf(red, green, blue)
  for (channel in 0 until 3) nits[channel] = srgbToLinear(nits[channel])
  writeHdrNits(nits[0], nits[1], nits[2], nits, 0)
  return nits
}

/**
 * Converts a packed sRGB [color] to linear BT.2020 display light, in cd/m2.
 *
 * The packed form of [authoredHdrNits], with alpha ignored.
 *
 * @return Red, green and blue in that order.
 */
@InternalFilmstripApi
public fun authoredHdrNits(color: Int): FloatArray =
  authoredHdrNits(
    channel(color, RED_SHIFT) / MAX_CODE,
    channel(color, GREEN_SHIFT) / MAX_CODE,
    channel(color, 0) / MAX_CODE,
  )

/**
 * Converts straight, unpremultiplied ARGB texels to the display light [authoredHdrNits] puts each
 * color at.
 *
 * Every eight-bit code decodes through a table of [srgbToLinear] built once, so a texel lands on the
 * same figure a single color of its codes does. The color is not premultiplied, a caller does that
 * after mapping.
 *
 * @return Four floats per texel, red, green and blue in cd/m2 and then alpha in the range zero to one.
 */
@InternalFilmstripApi
public fun authoredHdrTexels(argb: IntArray): FloatArray {
  val texels = FloatArray(argb.size * TEXEL_FLOATS)
  argb.forEachIndexed { index, color ->
    val offset = index * TEXEL_FLOATS
    writeHdrNits(
      SRGB_CODE_TO_LINEAR[channel(color, RED_SHIFT)],
      SRGB_CODE_TO_LINEAR[channel(color, GREEN_SHIFT)],
      SRGB_CODE_TO_LINEAR[channel(color, 0)],
      texels,
      offset,
    )
    texels[offset + 3] = channel(color, ALPHA_SHIFT) / MAX_CODE
  }
  return texels
}

private fun writeHdrNits(
  red: Float,
  green: Float,
  blue: Float,
  out: FloatArray,
  offset: Int,
) {
  for (row in 0 until 3) {
    val wide =
      BT709_TO_BT2020[row * 3] * red +
        BT709_TO_BT2020[row * 3 + 1] * green +
        BT709_TO_BT2020[row * 3 + 2] * blue

    out[offset + row] = (wide * HDR_REFERENCE_WHITE_NITS).toFloat()
  }
}

private fun channel(
  color: Int,
  shift: Int,
): Int = (color ushr shift) and 0xFF

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val ALPHA_SHIFT = 24
private const val MAX_CODE = 255f
private const val TEXEL_FLOATS = 4

private val SRGB_CODE_TO_LINEAR = FloatArray(256) { code -> srgbToLinear(codeLevel(code)) }

// A code's level stored in a FloatArray first, so the table on Kotlin/JS reads the same rounded figure
// authoredHdrNits does.
private fun codeLevel(code: Int): Float = floatArrayOf(code / MAX_CODE)[0]

// BT.709 and BT.2020 share a white point, so this is a change of primaries with no adaptation and
// every row sums to one.
private val BT709_TO_BT2020 =
  doubleArrayOf(
    0.6274039,
    0.3292830,
    0.0433131,
    0.0690973,
    0.9195404,
    0.0113623,
    0.0163914,
    0.0880133,
    0.8955953,
  )
