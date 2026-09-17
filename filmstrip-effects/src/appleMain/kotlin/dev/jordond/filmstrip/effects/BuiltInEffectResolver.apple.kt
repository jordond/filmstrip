package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
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
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.compositedOnto
import dev.jordond.filmstrip.effects.overlay.decode
import dev.jordond.filmstrip.effects.overlay.drawnTextSize
import dev.jordond.filmstrip.effects.overlay.frameWithin
import dev.jordond.filmstrip.effects.overlay.pixelSize
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rasterizeText
import dev.jordond.filmstrip.effects.overlay.runWithin
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
@OptIn(ExperimentalForeignApi::class, ExperimentalFilmstripApi::class)
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
      is Scale -> step { image, _ -> image.scaledToHeight(spec.targetHeight) }
      is ImageOverlay -> spec.toOverlay(attributes)
      is TextOverlay -> spec.toOverlay(capabilities, attributes)
      else -> spec.toColorStep()
    }
  }

  // Which specs are colour matrices is the shared lowering's answer rather than a list kept here
  // as well, brightness included: the cheap lowering a kept grade has for a plain scale is chosen
  // from the matrix's shape there rather than from the type here. The transfer is read off the frame
  // rather than off the plan, so a preview and the export it previews put the matrix in the same
  // domain.
  private fun EffectSpec.toColorStep(): EffectResolution? {
    val matrix = colorMatrixOf(this) ?: return null

    return step { image, frame ->
      image.withColorMatrix(matrix, frame.attributes.hdrTransfer, frame.attributes.colorSpace)
    }
  }

  // A region outside the frame samples nothing, and one with no area collapses the frame to a
  // point, so both are refused by name rather than drawn as whatever the reciprocal comes out as.
  private fun KenBurns.toStep(): EffectResolution {
    if (!from.isValid || !to.isValid) return EffectResolution.Unsupported(id, REGION_OUTSIDE_FRAME)
    return step { image, frame -> image.panned(regionAt(frame.compositionTime, frame.attributes.span)) }
  }

  // Rasterised at resolve and placed at apply. Where an overlay lands depends on the frame entering
  // it, and the preview and the export hand different frames to the same resolved effect, so a
  // placement settled here would be right on one path and wrong on the other. The run is not one of
  // those: the slot an effect is handed is settled before any frame is drawn, so it is derived once
  // here and every frame samples within it.
  private fun ImageOverlay.toOverlay(attributes: Attributes): EffectResolution {
    val raster = image.decode() ?: return EffectResolution.Unsupported(id, UNREADABLE_IMAGE)
    val size = raster.pixelSize()
    val run = runWithin(attributes.span)

    return step { image, frame ->
      val sampled = frameWithin(run, frame.compositionTime)
      if (sampled == null) {
        image
      } else {
        val input = frame.attributes.inputSize
        raster.compositedOnto(image, placedOn(input, size).animatedBy(sampled), input, sampled.opacity)
      }
    }
  }

  private fun TextOverlay.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    if (!capabilities.has(RenderFeature.TextRendering)) return EffectResolution.Unsupported(id, NO_TEXT_RENDERING)
    val raster =
      rasterizeText(text, style, attributes.layoutSize)
        ?: return EffectResolution.Unsupported(id, EMPTY_TEXT)
    val size = raster.pixelSize()
    val run = runWithin(attributes.span)

    return step { image, frame ->
      val sampled = frameWithin(run, frame.compositionTime)
      if (sampled == null) {
        image
      } else {
        // Laid out once against the frame an export writes and only resampled here, so a preview
        // and the export it previews break their lines on the same words. An animated scale
        // resamples that raster too rather than re-laying the glyphs at a new size.
        val drawn = frame.attributes.drawnTextSize(size)
        raster.compositedOnto(image, placedOn(drawn).animatedBy(sampled), frame.attributes.inputSize, sampled.opacity)
      }
    }
  }

  private fun step(block: (CIImage, FrameInfo) -> CIImage): EffectResolution =
    EffectResolution.Resolved(PlatformEffect(CoreImageEffect(block)))

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

private const val UNREADABLE_IMAGE =
  "The overlay image could not be decoded. Check that the path or URL is readable by this " +
    "process, and that the bytes are PNG, JPEG or HEIC."

private const val EMPTY_TEXT = "The text and its style leave nothing to draw."

private const val REGION_OUTSIDE_FRAME =
  "A pan travels between two regions of the frame, so both have to have area and lie inside it."

private const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."
