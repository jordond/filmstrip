package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.atOrigin
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.texelAffine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMake
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import kotlin.math.roundToInt

/**
 * This picture, stored as it is, cut to [crop], or kept whole when [crop] is null or keeps all of it.
 *
 * [crop] counts rows from the top, and Core Image from the bottom, so its rows are counted up from the bottom edge. Its
 * edges sit on whole texels, so every sample it keeps is kept as it is, and the result sits at the origin.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.cropped(crop: PictureCrop?): CIImage {
  val stored = pixelSize()
  if (crop == null || crop.keepsAll(stored)) return this
  val bottom = stored.height - crop.top - crop.size.height
  val (width, height) = crop.size.width.toDouble() to crop.size.height.toDouble()
  val rect = CGRectMake(crop.left.toDouble(), bottom.toDouble(), width, height)
  return atOrigin().imageByCroppingToRect(rect).atOrigin()
}

/**
 * This picture, stored as it is, laid out the way [orientation] shows it.
 *
 * The transform moves every stored texel onto the shown texel whose [PictureOrientation.sourceOf] names it, the
 * inverse of the map [texelAffine] reads off that function. Core Image measures from the bottom-left with +Y up, so
 * the stored picture is flipped into top-left rows first and the shown one flipped back after. Every texel centre
 * lands on a texel centre, so the picture keeps every sample and the result sits at the origin.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.oriented(orientation: PictureOrientation): CIImage {
  if (orientation == PictureOrientation.Normal) return this
  val stored = pixelSize()
  val shown = orientation.orientedSize(stored)
  val map = orientation.texelAffine(stored)
  val acrossX = map.acrossX.toDouble()
  val acrossY = map.acrossY.toDouble()
  val downX = map.downX.toDouble()
  val downY = map.downY.toDouble()
  val offsetX = map.offsetX
  val offsetY = map.offsetY
  val intoRows = CGAffineTransformMake(1.0, 0.0, 0.0, -1.0, 0.0, stored.height.toDouble())
  val turned =
    CGAffineTransformMake(
      acrossX,
      downX,
      acrossY,
      downY,
      -(acrossX * offsetX + acrossY * offsetY),
      -(downX * offsetX + downY * offsetY),
    )
  val backFromRows = CGAffineTransformMake(1.0, 0.0, 0.0, -1.0, 0.0, shown.height.toDouble())
  val transform = CGAffineTransformConcat(CGAffineTransformConcat(intoRows, turned), backFromRows)
  return atOrigin().imageByApplyingTransform(transform)
}

/**
 * The image's own pixel size.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.pixelSize(): Size =
  extent.useContents {
    Size(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
  }
