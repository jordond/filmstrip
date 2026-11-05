package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.withColorMatrix
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.regionAt
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.PictureRead
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.compositedOnto
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.drawnTextSize
import dev.jordond.filmstrip.effects.overlay.frameWithin
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rasterizeText
import dev.jordond.filmstrip.effects.overlay.read
import dev.jordond.filmstrip.effects.overlay.resolvedFrom
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import kotlin.math.PI

/**
 * Lowers the built-in catalogue onto Core Image.
 */
@OptIn(ExperimentalForeignApi::class)
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.CoreImage && capabilities.api != RenderApi.Metal) {
      return null
    }

    return when (spec) {
      is Rotate -> step { image, _ -> image.rotated(spec.degrees.toDouble()) }
      is Flip -> step { image, _ -> image.flipped(spec.axis) }
      is Crop -> step { image, frame -> image.cropped(spec.retainedRect(frame.attributes.inputSize)) }
      is CropRect -> step { image, _ -> image.cropped(spec.rect) }
      is KenBurns -> spec.toStep()
      is Scale -> step { image, _ -> image.scaledToHeight(spec.heightPx) }
      is ImageOverlay -> spec.toOverlay(attributes)
      is TextOverlay -> spec.toOverlay(capabilities, attributes)
      else -> spec.toColorStep()
    }
  }

  // Which specs are color matrices is the shared lowering's answer, brightness included: the
  // cheap lowering a kept grade has for a plain scale is chosen from the matrix's shape there.
  // The transfer is read off the frame rather than off the plan, so a preview and the export it
  // previews put the matrix in the same domain.
  private fun EffectSpec.toColorStep(): EffectResolution? {
    val matrix = colorMatrixOf(this) ?: return null

    return step { image, frame ->
      image.withColorMatrix(matrix, frame.attributes.hdrTransfer, frame.attributes.colorSpace)
    }
  }

  private fun KenBurns.toStep(): EffectResolution =
    step { image, frame -> image.panned(regionAt(frame.compositionTime, frame.attributes.span)) }

  // Rasterised at resolve and placed at apply. Where an overlay lands depends on the frame entering
  // it, and the preview and the export hand different frames to the same resolved effect, so a
  // placement settled here would be right on one path and wrong on the other. The drawn range is not one of
  // those: the slot an effect is handed is settled before any frame is drawn, so it is derived once
  // here and every frame samples within it.
  private fun ImageOverlay.toOverlay(attributes: Attributes): EffectResolution {
    val raster =
      when (val read = image.read()) {
        is PictureRead.Read -> read.value
        is PictureRead.Unreadable -> return EffectResolution.Unsupported(id, UNREADABLE_IMAGE)
        is PictureRead.Refused -> return EffectResolution.Unsupported(id, unreadSamples(read.reason))
        is PictureRead.Unsupported -> return EffectResolution.Unsupported(id, read.message)
      }
    val size = raster.size
    val drawnRange = drawnRangeWithin(attributes.span)

    return resolution(raster.limits) { image, frame ->
      val sampled = frameWithin(drawnRange, frame.compositionTime)
      if (sampled == null) {
        image
      } else {
        val input = frame.attributes.inputSize
        val placement = placedOn(input, size).animatedBy(sampled)
        raster.enteringFrame(frame.attributes.hdrTransfer, frame.attributes.colorSpace).compositedOnto(
          image,
          placement,
          input,
          sampled.opacity,
          frame.attributes.hdrTransfer,
          frame.attributes.colorSpace,
        )
      }
    }
  }

  private fun TextOverlay.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution =
    resolvedFrom(capabilities, { rasterizeText(text, style, attributes.layoutSize) }) { raster ->
      val size = raster.size
      val drawnRange = drawnRangeWithin(attributes.span)

      step { image, frame ->
        val sampled = frameWithin(drawnRange, frame.compositionTime)
        if (sampled == null) {
          image
        } else {
          // Laid out once against the frame an export writes and only resampled here, so a preview
          // and the export it previews break their lines on the same words. An animated scale
          // resamples that raster too rather than re-laying the glyphs at a new size.
          val drawn = frame.attributes.drawnTextSize(size)
          raster.enteringFrame(frame.attributes.hdrTransfer, frame.attributes.colorSpace).compositedOnto(
            image,
            placedOn(drawn).animatedBy(sampled),
            frame.attributes.inputSize,
            sampled.opacity,
            frame.attributes.hdrTransfer,
            frame.attributes.colorSpace,
          )
        }
      }
    }

  private fun step(block: (CIImage, FrameInfo) -> CIImage): EffectResolution = resolution(emptyList(), block)

  // Degraded by every cause in limits, and resolved exactly when there are none.
  private fun resolution(
    limits: List<Degradation>,
    block: (CIImage, FrameInfo) -> CIImage,
  ): EffectResolution {
    val effect = PlatformEffect(CoreImageEffect(block))
    return if (limits.isEmpty()) EffectResolution.Resolved(effect) else EffectResolution.Degraded(effect, limits)
  }

  private fun CIImage.rotated(degrees: Double): CIImage =
    imageByApplyingTransform(CGAffineTransformMakeRotation(degrees * PI / STRAIGHT_ANGLE)).atOrigin()

  private fun CIImage.flipped(axis: FlipAxis): CIImage =
    imageByApplyingTransform(
      when (axis) {
        FlipAxis.Horizontal -> CGAffineTransformMakeScale(-1.0, 1.0)
        FlipAxis.Vertical -> CGAffineTransformMakeScale(1.0, -1.0)
      },
    ).atOrigin()

  // filmstrip measures from the top-left with +Y down. Core Image measures from the bottom-left
  // with +Y up, so the Y axis flips on the way in.
  private fun CIImage.cropped(rect: NormalizedRect): CIImage =
    extent
      .useContents {
        this@cropped.imageByCroppingToRect(
          CGRectMake(
            x = origin.x + size.width * rect.left,
            y = origin.y + size.height * (1f - rect.bottom),
            width = size.width * rect.width,
            height = size.height * rect.height,
          ),
        )
      }.atOrigin()

  // The frame keeps the size it arrived at, matching what a vertex transform does on the other
  // backend: the region is cut out and then opened back up to the extent it was cut from.
  private fun CIImage.panned(rect: NormalizedRect): CIImage =
    cropped(rect)
      .imageByApplyingTransform(CGAffineTransformMakeScale(1.0 / rect.width, 1.0 / rect.height))
      .atOrigin()

  private fun CIImage.scaledToHeight(targetHeight: Int): CIImage =
    extent
      .useContents {
        if (size.height <= 0.0) {
          this@scaledToHeight
        } else {
          val factor = targetHeight / size.height
          this@scaledToHeight.imageByApplyingTransform(CGAffineTransformMakeScale(factor, factor))
        }
      }.atOrigin()
}

private const val STRAIGHT_ANGLE = 180.0

/**
 * The message an image overlay is refused with when its picture cannot be read or holds nothing ImageIO or Core Image
 * decodes.
 */
internal const val UNREADABLE_IMAGE =
  "The overlay image could not be decoded. Check that the path or URL is readable by this process, and that the " +
    "bytes hold a picture ImageIO or Core Image decodes on this OS, like PNG, JPEG, HEIC, AVIF, WebP or JPEG XL."

/**
 * The message an image overlay is refused with when ImageIO decodes its picture and its samples cannot be read, with
 * [reason] saying what ImageIO handed back.
 */
internal fun unreadSamples(reason: String): String =
  "The overlay image could not be read. ImageIO decoded the picture, and this backend draws the samples it decodes " +
    "to, which it could not read. $reason"
