package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.atOrigin
import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransform
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
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
 * Draws this overlay over [background], placed and faded as asked.
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
): CIImage =
  atOrigin()
    .imageByApplyingTransform(placement.transformOnto(frame, pixelSize()))
    .withAlpha(opacity)
    .imageByCompositingOverImage(background)

/**
 * Scales the whole image's alpha.
 *
 * Core Image works in premultiplied colour, so all four row vectors are scaled, the colour ones
 * included. Scaling alpha alone leaves the pixels brighter than their alpha says, and the composite
 * reads that as a halo.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.withAlpha(alpha: Float): CIImage {
  if (alpha >= 1f) return this
  val scale = alpha.coerceIn(0f, 1f).toDouble()

  return imageByApplyingFilter(
    "CIColorMatrix",
    mapOf(
      "inputRVector" to CIVector.vectorWithX(scale, 0.0, 0.0, 0.0),
      "inputGVector" to CIVector.vectorWithX(0.0, scale, 0.0, 0.0),
      "inputBVector" to CIVector.vectorWithX(0.0, 0.0, scale, 0.0),
      "inputAVector" to CIVector.vectorWithX(0.0, 0.0, 0.0, scale),
    ),
  )
}
