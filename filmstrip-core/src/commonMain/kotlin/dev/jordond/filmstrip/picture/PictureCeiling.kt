package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The most bytes reading, assembling or drawing one overlay picture may hold at its peak, 1 GiB.
 *
 * Apple counts its reads and its draws at their color against it, a browser counts decoding a PNG's samples beside
 * the bitmap drawn from it, and a text raster on every backend counts itself, the floats an HDR frame draws it from
 * and any copies held on the way. A HEIF image overlay counts the area its canvas and inputs span when it is read, so
 * a backend that assembles one never starts on a frame past it. A picture or text whose count passes it is refused by
 * name, since iOS ends an app past its memory limit without malloc ever failing and a browser tab runs out the same
 * way. Every backend holds a path or URI picture's encoded bytes under it too.
 */
@InternalFilmstripApi
public const val PICTURE_PEAK_CEILING: Long = 1L shl 30

/**
 * The bytes a read or a draw of an overlay picture or a text raster holds at its peak, or null when that count does
 * not fit in a [Long] or any part of it is null or negative.
 *
 * Every count made against [PICTURE_PEAK_CEILING] is made this way. [held] is what the step works from, which stays
 * held the whole time. [band] is everything else the step holds on the way, such as copies of the samples or one band
 * of rows. [made] is what the step writes. The three are held together at the peak, so the count is their sum.
 */
@InternalFilmstripApi
public fun picturePeakBytes(
  held: Long?,
  band: Long?,
  made: Long?,
): Long? {
  if (held == null || band == null || made == null) return null
  if (held < 0 || band < 0 || made < 0) return null
  if (held > Long.MAX_VALUE - band || held + band > Long.MAX_VALUE - made) return null
  return held + band + made
}
