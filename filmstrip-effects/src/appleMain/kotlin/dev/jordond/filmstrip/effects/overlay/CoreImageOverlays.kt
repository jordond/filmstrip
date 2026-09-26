package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.atOrigin
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransform
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector

/**
 * The transform that puts an overlay where this placement says.
 *
 * The rectangle comes from [rectOn], so the anchor pair is brought together in one place rather
 * than once per backend. Core Image measures real pixels from the bottom-left with +Y up and
 * filmstrip authors fractions from the top-left with +Y down, so the rectangle's bottom edge is
 * what the translation is taken from.
 *
 * @param frame The frame the overlay lands on, in pixels.
 * @param raster The overlay's own pixel size, which the drawn size is scaled from.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun OverlayPlacement.transformOnto(
  frame: Size,
  raster: Size,
): CValue<CGAffineTransform> {
  val rect = rectOn(frame)
  val scaleX = if (raster.width <= 0) 1.0 else size.width.toDouble() / raster.width
  val scaleY = if (raster.height <= 0) 1.0 else size.height.toDouble() / raster.height
  val translateX = rect.left.toDouble() * frame.width
  val translateY = (1.0 - rect.bottom.toDouble()) * frame.height

  return CGAffineTransformConcat(
    CGAffineTransformMakeScale(scaleX, scaleY),
    CGAffineTransformMakeTranslation(translateX, translateY),
  )
}

/**
 * An overlay's pixels, held once as Core Image decodes them and once as bare codes.
 *
 * An SDR frame draws an authored colour as its own codes, with no colour management, so there the
 * bare codes are tagged with the frame's own code space and come out of the blend where they were
 * authored. An HDR frame draws [managed], decoded through whatever colour space the source names.
 *
 * @property managed The pixels as Core Image decodes them, colour matched from the source's space.
 * @property codes The same pixels with no colour space attached.
 */
internal class OverlayPixels(
  val managed: CIImage,
  val codes: CIImage,
) {
  val size: Size = codes.pixelSize()

  /**
   * The image to draw onto a frame written in [colorSpace] with [transfer], or with none when the
   * frame is SDR.
   */
  fun enteringFrame(
    transfer: HdrTransfer?,
    colorSpace: ColorSpace,
  ): CIImage = if (transfer != null) managed else codes.taggedWithCodesOf(colorSpace)
}

// Decoded through the same space inCodesOf encodes into, so carrying the result into those codes
// again hands back the values it started with.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.taggedWithCodesOf(colorSpace: ColorSpace): CIImage {
  val space = colorSpace.createCodeSpace()

  try {
    return imageByColorMatchingColorSpaceToWorkingSpace(space) ?: this
  } finally {
    CGColorSpaceRelease(space)
  }
}

/**
 * Draws this overlay over [background], placed and faded as asked.
 *
 * Core Image's working space is linear light. An SDR frame, where [transfer] is null, blends on the
 * codes of its own [colorSpace] instead, the values every other backend reads off the buffer and
 * draws an overlay on, so both images are carried into those codes for the source over and the
 * result is carried back to the working space. An HDR frame blends in the working space as it is.
 *
 * Colour matching unpremultiplies before it moves a colour and premultiplies after, so the soft
 * edge of a glyph or an image blends by its own alpha rather than fringing.
 *
 * Overlays are not batched the way Android's are. Core Image fuses a run of composites into one
 * pass over the frame, so there is no sampler budget to spend and no analogue of
 * [MAX_OVERLAYS_PER_EFFECT].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.compositedOnto(
  background: CIImage,
  placement: OverlayPlacement,
  frame: Size,
  opacity: Float,
  transfer: HdrTransfer?,
  colorSpace: ColorSpace,
): CIImage {
  val placed = atOrigin().imageByApplyingTransform(placement.transformOnto(frame, pixelSize()))
  if (transfer != null) return placed.withAlpha(opacity).imageByCompositingOverImage(background)

  return inCodesOf(colorSpace) { toCodes ->
    toCodes(placed).withAlpha(opacity).imageByCompositingOverImage(toCodes(background))
  }
}

/**
 * Scales the whole image's alpha.
 *
 * `CIColorMatrix` unpremultiplies its input and premultiplies its output, so scaling the alpha row
 * is all it takes to scale how much of the frame the overlay covers. Scaling the colour rows as
 * well dims the colour a second time on the way back, which draws the overlay at the square of the
 * alpha it was asked for.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.withAlpha(alpha: Float): CIImage {
  if (alpha >= 1f) return this
  val scale = alpha.coerceIn(0f, 1f).toDouble()

  return imageByApplyingFilter(
    "CIColorMatrix",
    mapOf("inputAVector" to CIVector.vectorWithX(0.0, 0.0, 0.0, scale)),
  )
}
