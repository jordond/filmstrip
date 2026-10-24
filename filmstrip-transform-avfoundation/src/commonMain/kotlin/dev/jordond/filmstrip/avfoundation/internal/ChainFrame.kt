package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.linearDimGain
import dev.jordond.filmstrip.transform.internal.coverScale
import dev.jordond.filmstrip.transform.internal.sigmaFor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVMakeRectWithAspectRatioInsideRect
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectInset
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.CoreImage.kCIInputScaleKey
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bakes the container's rotation into the pixels.
 *
 * Apple records orientation in the track's preferred transform, and the flag that applies it is
 * ignored the moment a video composition is set. `displaySize` is already the rotated frame, so
 * every effect after this measures against the frame it was resolved for.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.rotated(degrees: Int): CIImage {
  if (degrees % FULL_TURN == 0) return this
  return imageByApplyingTransform(CGAffineTransformMakeRotation(degrees * PI / STRAIGHT_ANGLE)).atOrigin()
}

/**
 * Lays the frame into the output rect and composites it over the fill.
 *
 * The composite is not only for [Fit.Contain]'s bars. `finishWithImage` wants an image covering
 * the whole render rect, and a frame that lands a pixel short of one edge after rounding leaves
 * AVFoundation to fill the difference with whatever the buffer already held.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.fittedTo(
  size: Size,
  fit: Fit,
  fill: Fill,
  transfer: HdrTransfer?,
): CIImage {
  val rect = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
  val background = fillImage(fill, size, transfer).imageByCroppingToRect(rect)
  return laidInto(size, fit).imageByCompositingOverImage(background)
}

/**
 * Lays this frame into the output rect at [fit], with nothing composited behind it yet.
 *
 * Split out from [fittedTo] so a caller that has to grade the frame before the fill is drawn in
 * can run this step on its own, and paint the fill in only afterwards.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.laidInto(
  size: Size,
  fit: Fit,
): CIImage {
  val width = size.width.toDouble()
  val height = size.height.toDouble()
  val rect = CGRectMake(0.0, 0.0, width, height)

  val laid =
    extent
      .useContents {
        if (this.size.width <= 0.0 || this.size.height <= 0.0) return@useContents null

        val scaleX = width / this.size.width
        val scaleY = height / this.size.height
        val (sx, sy) =
          when (fit) {
            Fit.Contain -> min(scaleX, scaleY).let { it to it }
            Fit.Crop -> max(scaleX, scaleY).let { it to it }
            Fit.Stretch -> scaleX to scaleY
          }

        val dx = (width - this.size.width * sx) / 2.0
        val dy = (height - this.size.height * sy) / 2.0
        CGAffineTransformConcat(
          CGAffineTransformConcat(
            CGAffineTransformMakeTranslation(-origin.x, -origin.y),
            CGAffineTransformMakeScale(sx, sy),
          ),
          CGAffineTransformMakeTranslation(dx, dy),
        )
      }?.let(::imageByApplyingTransform) ?: this

  return laid.imageByCroppingToRect(rect)
}

/**
 * Blends this graded frame over [background], letting [mask]'s alpha decide where.
 *
 * `finishWithImage` wants an image covering the whole render rect, so the fill still has to be
 * composited in even when nothing shows it, just after the composition effects run instead of
 * before them. A plain source-over would add a graded pixel's garbage colour to the background at
 * alpha zero. `CIBlendWithAlphaMask` lerps on the mask's alpha instead, discarding it the way
 * every other backend's flatten does.
 *
 * A solid fill is an image of infinite extent, so it is cropped to [size] before the blend reads
 * it and the result covers the render rect rather than everything beyond it too.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.over(
  background: CIImage,
  mask: CIImage,
  size: Size,
): CIImage {
  val rect = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
  return imageByApplyingFilter(
    "CIBlendWithAlphaMask",
    mapOf(
      "inputBackgroundImage" to background.imageByCroppingToRect(rect),
      "inputMaskImage" to mask,
    ),
  )
}

/**
 * Lowers a [Fill] to the image it paints, drawn from this frame where the fill needs one.
 *
 * A fill this module does not recognise falls back to black rather than failing, since [Fill]
 * gains new arms over time and an older backend has to keep working against one it predates.
 */
@Suppress("REDUNDANT_ELSE_IN_WHEN")
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.fillImage(
  fill: Fill,
  size: Size,
  transfer: HdrTransfer?,
): CIImage =
  when (fill) {
    is Fill.Solid -> CIImage(color = fillCIColor(fill.color, transfer))
    is Fill.Blurred -> blurredCover(size, fill)
    else -> CIImage(color = CIColor.blackColor)
  }

/**
 * Scales this frame to cover [size], blurs it and crops away the blur's own soft edge.
 *
 * The blur runs before the scale up to cover, over this frame's own resolution rather than the
 * output's, so a 4K source costs the same blur as a 720p one. [Fill.Blurred.radius] is a
 * fraction of the output frame's shorter side, so it is converted to an output pixel radius
 * first and then divided back through the cover scale, landing at the size it was asked for
 * once the scale runs.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.blurredCover(
  size: Size,
  blur: Fill.Blurred,
): CIImage {
  val width = size.width.toDouble()
  val height = size.height.toDouble()
  val source = extent
  val (sourceWidth, sourceHeight) = source.useContents { this.size.width to this.size.height }
  if (sourceWidth <= 0.0 || sourceHeight <= 0.0) return CIImage(color = CIColor.blackColor)

  val outputRadius = blur.sigmaFor(size).toDouble()
  val sourceSize = Size(sourceWidth.roundToInt(), sourceHeight.roundToInt())
  val scale = coverScale(sourceSize, size).toDouble()

  // Clamping before the blur and cropping back to the original extent afterwards keeps the
  // Gaussian from pulling in transparent black at the edges, which would otherwise darken them.
  val blurred =
    imageByClampingToExtent()
      .imageByApplyingGaussianBlurWithSigma(outputRadius / scale)
      .imageByCroppingToRect(source)

  val covered =
    blurred
      .imageByApplyingFilter("CILanczosScaleTransform", mapOf(kCIInputScaleKey to scale))
      .trimmedToCover(width, height, margin = 2 * outputRadius)

  return if (blur.dim <= 0f) covered else covered.dimmed(blur.dim)
}

/**
 * Crops a [margin] off every side, then scales what remains up to exactly cover [width] by
 * [height].
 *
 * This receiver already covers that frame, so the crop trims off the blur's own soft boundary
 * before the scale spreads the sharp remainder back across the whole output.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.trimmedToCover(
  width: Double,
  height: Double,
  margin: Double,
): CIImage {
  val trimmed = CGRectInset(extent, margin, margin)
  val fitted = AVMakeRectWithAspectRatioInsideRect(CGSizeMake(width, height), trimmed)
  return fitted.useContents {
    val scale = width / this.size.width
    val transform =
      CGAffineTransformConcat(
        CGAffineTransformConcat(
          CGAffineTransformMakeTranslation(
            -(origin.x + this.size.width / 2.0),
            -(origin.y + this.size.height / 2.0),
          ),
          CGAffineTransformMakeScale(scale, scale),
        ),
        CGAffineTransformMakeTranslation(width / 2.0, height / 2.0),
      )
    this@trimmedToCover.imageByApplyingTransform(transform)
  }
}

/**
 * Darkens this image's colour channels by [dim], leaving its alpha untouched.
 *
 * `CIColorMatrix` runs on linear light rather than on the encoded value a dim is defined against,
 * so the shared gain for a linear pipeline is what goes in here. Multiplying by the encoded gain
 * directly would land lighter than it was asked to.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.dimmed(dim: Float): CIImage {
  val scale = linearDimGain(dim).toDouble()
  return imageByApplyingFilter(
    "CIColorMatrix",
    mapOf(
      "inputRVector" to CIVector.vectorWithX(scale, 0.0, 0.0, 0.0),
      "inputGVector" to CIVector.vectorWithX(0.0, scale, 0.0, 0.0),
      "inputBVector" to CIVector.vectorWithX(0.0, 0.0, scale, 0.0),
    ),
  )
}

// Clear over this image's own extent, so geometry measuring the frame still sees its size.
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.cleared(): CIImage = CIImage(color = CIColor.clearColor).imageByCroppingToRect(extent)

// Core Image transforms about the coordinate origin, so a rotation pushes the extent into
// negative space and it has to be brought back.
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.atOrigin(): CIImage =
  extent.useContents {
    this@atOrigin.imageByApplyingTransform(CGAffineTransformMakeTranslation(-origin.x, -origin.y))
  }

private const val STRAIGHT_ANGLE = 180.0
private const val FULL_TURN = 360
