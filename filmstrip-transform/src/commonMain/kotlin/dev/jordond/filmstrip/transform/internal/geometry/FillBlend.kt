package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.roundToInt

/**
 * The code a straight alpha pixel reads as once it is laid over what the fill paints behind it.
 *
 * This is source-over on the stored codes of one channel, `source * alpha + fill * (1 - alpha)`, rounded to the
 * nearest code. A solid fill passes its own channel as [fill], and a blurred fill passes the same channel of the
 * blurred cover at that pixel, so one formula answers both and every backend is held to it.
 *
 * @param source The channel of the pixel as the clip decoded it, from 0 to 255, before alpha is applied.
 * @param alpha The pixel's alpha, from 0 for clear to 255 for opaque.
 * @param fill The same channel of the fill behind the pixel, from 0 to 255.
 */
@InternalFilmstripApi
public fun blendOverFill(
  source: Int,
  alpha: Int,
  fill: Int,
): Int {
  val covered = alpha.coerceIn(0, OPAQUE) / OPAQUE.toDouble()
  return (source * covered + fill * (1.0 - covered)).roundToInt()
}

private const val OPAQUE = 255
