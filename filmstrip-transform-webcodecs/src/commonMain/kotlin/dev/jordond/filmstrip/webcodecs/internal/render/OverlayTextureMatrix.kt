package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.texelAffine

/**
 * The matrix that carries a point in a raster stored at [stored], as the shown picture's texture coordinates, to the
 * point it reads in the raster as uploaded, the way [orientation] lays the raster out. The nine floats of a
 * column-major `mat3`, both points counted from the top left corner as fractions of their picture's sides.
 *
 * It is [texelAffine] scaled to fractions of each picture's sides. The centre of every shown texel lands on the centre
 * of the stored texel [PictureOrientation.sourceOf] names, so the drawn picture holds the texels
 * [dev.jordond.filmstrip.picture.orientedSamples] lays out.
 */
internal fun textureMatrixOf(
  orientation: PictureOrientation,
  stored: Size,
): FloatArray {
  val shown = orientation.orientedSize(stored)
  val map = orientation.texelAffine(stored)
  val acrossX = map.acrossX.toFloat()
  val acrossY = map.acrossY.toFloat()
  val downX = map.downX.toFloat()
  val downY = map.downY.toFloat()
  val offsetX = map.offsetX.toFloat()
  val offsetY = map.offsetY.toFloat()
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

/**
 * The window of the shown picture an overlay reads, for the shader that clamps to it.
 *
 * The six floats are the window's left, top, width and height as fractions of the shown picture's sides, then half a
 * texel as a fraction of its width and of its height. Read through [textureMatrixOf], a point clamped half a texel
 * inside the window reads the texel centres at the window's edge and no texel beside it, however the picture is
 * turned. With no [cell] the window is the whole picture and half a texel is none, which leaves a read as it was.
 *
 * @param cell The rectangle of the shown picture to draw, or null for all of it.
 * @param shown The size of the picture as shown.
 */
internal fun cellWindowOf(
  cell: PictureCrop?,
  shown: Size,
): FloatArray {
  if (cell == null) return floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f)
  val width = shown.width.toFloat()
  val height = shown.height.toFloat()
  return floatArrayOf(
    cell.left / width,
    cell.top / height,
    cell.size.width / width,
    cell.size.height / height,
    HALF_TEXEL / width,
    HALF_TEXEL / height,
  )
}

private const val HALF_TEXEL = 0.5f
