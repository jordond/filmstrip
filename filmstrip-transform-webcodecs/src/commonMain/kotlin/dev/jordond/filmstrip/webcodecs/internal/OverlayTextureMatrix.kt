package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PictureOrientation

/**
 * The matrix that carries a point in a raster stored at [stored], as the shown picture's texture coordinates, to the
 * point it reads in the raster as uploaded, the way [orientation] lays the raster out. The nine floats of a
 * column-major `mat3`, both points counted from the top left corner as fractions of their picture's sides.
 *
 * It is read off [PictureOrientation.sourceOf] at three texels. The centre of every shown texel lands on the centre of
 * the stored texel that function names, so the drawn picture holds the texels
 * [dev.jordond.filmstrip.media.orientedSamples] lays out.
 */
internal fun textureMatrixOf(
  orientation: PictureOrientation,
  stored: Size,
): FloatArray {
  val shown = orientation.orientedSize(stored)
  val origin = orientation.sourceOf(0, 0, shown)
  val across = orientation.sourceOf(1, 0, shown)
  val down = orientation.sourceOf(0, 1, shown)
  val acrossX = (across.x - origin.x).toFloat()
  val acrossY = (across.y - origin.y).toFloat()
  val downX = (down.x - origin.x).toFloat()
  val downY = (down.y - origin.y).toFloat()
  val offsetX = origin.x + HALF_TEXEL - HALF_TEXEL * (acrossX + downX)
  val offsetY = origin.y + HALF_TEXEL - HALF_TEXEL * (acrossY + downY)
  val width = stored.width.toFloat()
  val height = stored.height.toFloat()
  return floatArrayOf(
    acrossX * shown.width / width,
    acrossY * shown.width / height,
    0f,
    downX * shown.height / width,
    downY * shown.height / height,
    0f,
    offsetX / width,
    offsetY / height,
    1f,
  )
}

private const val HALF_TEXEL = 0.5f
