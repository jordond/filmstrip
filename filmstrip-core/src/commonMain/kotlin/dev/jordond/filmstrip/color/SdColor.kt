package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The color primaries code point of SMPTE C, the primaries an NTSC SD source sits on.
 */
@InternalFilmstripApi
public const val SMPTE_C_PRIMARIES: Int = 6

/**
 * The color primaries code point of EBU Tech 3213, the primaries a PAL SD source sits on.
 */
@InternalFilmstripApi
public const val EBU_PRIMARIES: Int = 5

/**
 * The color primaries code points an SD source sits on, the ones [sdPrimariesToBt709] moves off onto BT.709's.
 */
@InternalFilmstripApi
public val SD_PRIMARIES: Set<Int> = setOf(SMPTE_C_PRIMARIES, EBU_PRIMARIES)

/**
 * The matrix taking linear light on color primaries code point [primaries] to linear light on BT.709's, stored row by
 * row. It holds `P709^-1 * Pn`, where `P` takes linear RGB to XYZ, and is the identity for 1.
 *
 * @throws IllegalArgumentException When core has no chromaticities for [primaries], or their white is not D65's.
 */
@InternalFilmstripApi
public fun sdPrimariesToBt709(primaries: Int): DoubleArray {
  val chromaticities = cicpChromaticities(primaries)
  require(chromaticities != null) { "Filmstrip has no chromaticities for color primaries $primaries." }
  require(xyToXyz(chromaticities[6], chromaticities[7]).contentEquals(D65_WHITE)) {
    "Color primaries $primaries are not on D65."
  }
  return multiply(BT709_TO_XYZ_INVERSE, checkNotNull(rgbToXyz(chromaticities)))
}

/**
 * The BT.709 signal a source's signal [rgb] displays as, each channel in the range zero to one.
 *
 * [rgb] is what the source decodes to under its own matrix, on color primaries code point [primaries]. It goes to
 * linear light through BT.709's curve, across [sdPrimariesToBt709] and back, and a channel landing outside zero to one
 * is clamped onto it. Keeping a source's codes and tags displays this colour too, read out through the same function.
 *
 * @throws IllegalArgumentException For the [primaries] [sdPrimariesToBt709] refuses, or [rgb] not holding three values.
 */
@InternalFilmstripApi
public fun sdSourceDisplayed(
  rgb: DoubleArray,
  primaries: Int,
): DoubleArray {
  require(rgb.size == RGB_CHANNELS) { "A colour holds $RGB_CHANNELS channels, and ${rgb.size} were given." }
  val light = apply(sdPrimariesToBt709(primaries), DoubleArray(RGB_CHANNELS) { BT709_CURVE.decode(rgb[it]) })
  return DoubleArray(RGB_CHANNELS) { BT709_CURVE.encode(light[it].coerceIn(0.0, 1.0)) }
}

private const val BT709_CODE_POINT = 1
private const val RGB_CHANNELS = 3

private val BT709_CURVE = CicpOetf(BT709_CODE_POINT)
private val BT709_TO_XYZ_INVERSE =
  checkNotNull(invert(checkNotNull(rgbToXyz(checkNotNull(cicpChromaticities(BT709_CODE_POINT))))))
