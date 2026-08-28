package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.roundToInt

/**
 * Turns a track's stored dimensions into the ones a player puts on screen.
 *
 * The pixel aspect grows the stored frame and the rotation turns the result, in that order, which
 * is the order a player applies them in. Wide pixels grow the width, tall pixels grow the height,
 * so the correction never resamples a side down and away. A ratio of `1f` and a rotation of zero
 * hand back [codedSize] unchanged.
 *
 * @param codedSize Storage dimensions, as the container reports them.
 * @param rotationDegrees Rotation the container asks a player to apply: 0, 90, 180 or 270.
 * @param pixelAspectRatio Width of a stored pixel over its height. A ratio far enough outside the
 *   band real containers report is a bad read rather than a real frame, and is taken as square.
 */
@InternalFilmstripApi
public fun displaySizeOf(
  codedSize: Size,
  rotationDegrees: Int,
  pixelAspectRatio: Float,
): Size {
  val grown =
    when {
      pixelAspectRatio !in PLAUSIBLE_RATIOS -> codedSize
      pixelAspectRatio > 1f -> Size((codedSize.width * pixelAspectRatio).roundToInt(), codedSize.height)
      else -> Size(codedSize.width, (codedSize.height / pixelAspectRatio).roundToInt())
    }

  return if (rotationDegrees % HALF_TURN == 0) grown else Size(grown.height, grown.width)
}

/**
 * The band a real container reports a pixel aspect in. Every ratio in use sits well inside it, and
 * a value outside it grows a frame by orders of magnitude rather than correcting one.
 */
private val PLAUSIBLE_RATIOS = 1f / 16f..16f

private const val HALF_TURN = 180
