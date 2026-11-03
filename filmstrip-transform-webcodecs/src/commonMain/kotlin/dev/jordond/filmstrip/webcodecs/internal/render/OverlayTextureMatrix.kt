package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.geometry.Size
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
