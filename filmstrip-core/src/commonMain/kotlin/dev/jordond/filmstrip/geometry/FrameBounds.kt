package dev.jordond.filmstrip.geometry

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.roundToInt

/**
 * The largest frame a composition that takes its frame from a still asks for.
 *
 * A still's pixel bounds come off a camera sensor rather than out of an encoder, and a phone photo
 * is several times the frame consumer video pipelines are built around. An encoder that publishes
 * its limits as one range per side accepts such a frame on paper and then spends minutes on it or
 * runs the device out of memory, so a frame with no encoder to measure from is held here as well as
 * to the encoder's own ceiling.
 */
@InternalFilmstripApi
public val MAX_STILL_FRAME: Size = Size(3840, 2160)

/**
 * The largest frame with [size]'s aspect that fits inside [bounds], never larger than [size].
 *
 * Both sides scale by the same factor, so the frame keeps its shape. Coercing each side on its own
 * would land a 4:3 frame on a 16:9 ceiling as a 16:9 one, which changes the picture rather than its
 * size. A frame that already fits comes back untouched.
 */
@InternalFilmstripApi
public fun frameWithin(
  size: Size,
  bounds: Size,
): Size {
  if (size.width <= 0 || size.height <= 0 || bounds.width <= 0 || bounds.height <= 0) return size
  if (size.width <= bounds.width && size.height <= bounds.height) return size

  val scale =
    minOf(
      bounds.width.toFloat() / size.width.toFloat(),
      bounds.height.toFloat() / size.height.toFloat(),
    )
  return Size(
    (size.width * scale).roundToInt().coerceIn(1, bounds.width),
    (size.height * scale).roundToInt().coerceIn(1, bounds.height),
  )
}
