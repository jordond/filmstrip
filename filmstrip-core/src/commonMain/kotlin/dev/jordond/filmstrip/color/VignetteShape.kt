package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.sqrt

/**
 * What a vignette multiplies the codes of the pixel at [x], [y] by, from 1 at the centre down to `1 - strength`.
 *
 * [x] and [y] are fractions of the frame the vignette runs on, with 0.5 at its centre. The distance from the centre
 * scales x by [aspect] so the shape is round on screen, and is divided by the distance to a corner so a corner sits at
 * 1. The gain falls off along a smoothstep from `radius - softness` to `radius + softness` of that distance, and a
 * [softness] of 0 is a hard edge at [radius].
 *
 * @param aspect The frame's width over its height.
 * @param strength How much of the codes the darkest part takes away, from 0 to 1.
 * @param radius The distance where the falloff is half way, as a fraction of the centre to a corner.
 * @param softness How far either side of [radius] the falloff reaches.
 */
@InternalFilmstripApi
public fun vignetteGain(
  x: Double,
  y: Double,
  aspect: Double,
  strength: Double,
  radius: Double,
  softness: Double,
): Double {
  val dx = (x - CENTRE) * aspect
  val dy = y - CENTRE
  val corner = sqrt(CENTRE * aspect * CENTRE * aspect + CENTRE * CENTRE)
  val distance = sqrt(dx * dx + dy * dy) / corner
  return 1.0 - strength * falloff(radius - softness, radius + softness, distance)
}

// GLSL's smoothstep, with an edge that has no width read as a step at it.
private fun falloff(
  from: Double,
  to: Double,
  value: Double,
): Double {
  if (to <= from) return if (value < from) 0.0 else 1.0
  val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
  return t * t * (3.0 - 2.0 * t)
}

private const val CENTRE = 0.5
