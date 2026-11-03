package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.pow

// The sRGB transfer function an authored color is written in. Every caller that turns an authored
// code into light, or light back into a code, reads the curve from here.

/**
 * Decodes an sRGB encoded channel into linear light, both in the range zero to one.
 *
 * Runs the linear segment below the knee and the 2.4 power above it, as IEC 61966-2-1 defines them.
 * A value outside zero to one is clamped first.
 */
@InternalFilmstripApi
public fun srgbToLinear(encoded: Float): Float {
  val value = encoded.coerceIn(0f, 1f).toDouble()

  return if (value <= SRGB_ENCODED_KNEE) {
    value / SRGB_SLOPE
  } else {
    ((value + SRGB_OFFSET) / (1.0 + SRGB_OFFSET)).pow(SRGB_GAMMA)
  }.toFloat()
}

/**
 * Encodes linear light as an sRGB channel, both in the range zero to one.
 *
 * The inverse of [srgbToLinear], so a channel decoded and encoded again lands where it started.
 * A value outside zero to one is clamped first.
 */
@InternalFilmstripApi
public fun linearToSrgb(light: Float): Float {
  val value = light.coerceIn(0f, 1f).toDouble()

  return if (value <= SRGB_LINEAR_KNEE) {
    value * SRGB_SLOPE
  } else {
    (1.0 + SRGB_OFFSET) * value.pow(1.0 / SRGB_GAMMA) - SRGB_OFFSET
  }.toFloat()
}

internal const val SRGB_ENCODED_KNEE = 0.04045
internal const val SRGB_LINEAR_KNEE = 0.0031308
internal const val SRGB_SLOPE = 12.92
internal const val SRGB_OFFSET = 0.055
internal const val SRGB_GAMMA = 2.4
