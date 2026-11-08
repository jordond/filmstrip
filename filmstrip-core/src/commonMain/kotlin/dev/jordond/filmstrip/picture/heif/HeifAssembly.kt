package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.picturePeakBytes

/**
 * The bytes one texel of a `grid` or `iovl` picture's canvas or of one of its inputs is counted at, four sixteen-bit
 * samples.
 *
 * It is the widest a backend puts one of those pictures together at, so a count made with it holds for every backend.
 */
@InternalFilmstripApi
public const val ASSEMBLY_TEXEL_BYTES: Long = 8L

// The bytes putting a picture of [canvas] together from [inputs] holds at its peak, every input frame held at its own
// size while the canvas is made, or null when that count does not fit in a Long.
internal fun assemblyPeakBytes(
  canvas: Size,
  inputs: List<Size>,
): Long? {
  var held: Long? = 0L
  for (input in inputs) held = picturePeakBytes(held, 0L, texelBytes(input))
  return picturePeakBytes(held, 0L, texelBytes(canvas))
}

/**
 * The most bytes one input frame may hold beside a `grid` or `iovl` canvas of [canvas] while the picture is put
 * together one frame at a time, what [PICTURE_PEAK_CEILING] leaves once the canvas is counted at [ASSEMBLY_TEXEL_BYTES]
 * a texel.
 *
 * It is zero when the canvas alone reaches the ceiling, so no frame fits beside it.
 */
@InternalFilmstripApi
public fun assemblyFrameBudget(canvas: Size): Long {
  val held = texelBytes(canvas) ?: return 0L
  return (PICTURE_PEAK_CEILING - held).coerceAtLeast(0L)
}

private fun texelBytes(size: Size): Long? {
  val area = size.width.toLong() * size.height
  return if (area > Long.MAX_VALUE / ASSEMBLY_TEXEL_BYTES) null else area * ASSEMBLY_TEXEL_BYTES
}
