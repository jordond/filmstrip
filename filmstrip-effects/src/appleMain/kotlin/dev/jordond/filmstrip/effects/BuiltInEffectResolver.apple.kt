package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
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
      is Brightness -> step { image, frame -> image.withBrightness(spec.scale, frame.attributes.hdrTransfer) }
      is Watermark -> spec.toOverlay()
      is Text -> spec.toOverlay(capabilities, attributes)
      else -> null
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
  // placement settled here would be right on one path and wrong on the other.
  private fun Watermark.toOverlay(): EffectResolution {
    val raster = image.decode() ?: return EffectResolution.Unsupported(id, UNREADABLE_IMAGE)
    val size = raster.pixelSize()

    return step { image, frame ->
      if (!frame.shows(visibleDuring)) {
        image
      } else {
        val input = frame.attributes.inputSize
        raster.compositedOnto(image, placedOn(input, size), input, opacity)
      }
    }
  }

  private fun Text.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    if (!capabilities.has(RenderFeature.TextRendering)) return EffectResolution.Unsupported(id, NO_TEXT_RENDERING)
    val raster =
      rasterizeText(text, style, attributes.layoutSize)
        ?: return EffectResolution.Unsupported(id, EMPTY_TEXT)
    val size = raster.pixelSize()

    return step { image, frame ->
      if (!frame.shows(visibleDuring)) {
        image
      } else {
        // Laid out once against the frame an export writes and only resampled here, so a preview
        // and the export it previews break their lines on the same words.
        val drawn = frame.attributes.drawnTextSize(size)
        raster.compositedOnto(image, placedOn(drawn), frame.attributes.inputSize, 1f)
      }
    }
  }

  /**
   * Whether an overlay timed to [range] is drawn on this frame.
   *
   * The composition's timeline is the base on every backend. media3 hands its overlays a
   * presentation time, ffmpeg gates on the filtergraph's `t`, and Core Image gets it from the
   * filter request. A null range is the whole composition.
   */
  private fun FrameInfo.shows(range: TimeRange?): Boolean = range == null || compositionTime in range

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
  "The watermark image could not be decoded. Check that the path or URL is readable by this " +
    "process, and that the bytes are PNG, JPEG or HEIC."

private const val EMPTY_TEXT = "The text and its style leave nothing to draw."

private const val REGION_OUTSIDE_FRAME =
  "A pan travels between two regions of the frame, so both have to have area and lie inside it."

private const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."
