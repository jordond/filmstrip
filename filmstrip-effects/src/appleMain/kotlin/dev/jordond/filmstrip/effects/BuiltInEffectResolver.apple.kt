package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.ExecutionContext
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Lowers the built-in catalogue onto Core Image.
 */
@OptIn(ExperimentalForeignApi::class)
public actual class BuiltInEffectResolver actual constructor(
  @Suppress("unused") private val context: PlatformContext,
) : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
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
      is Scale -> step { image, _ -> image.scaledToHeight(spec.targetHeight) }
      is Brightness -> step { image, frame -> image.withBrightness(spec.scale, frame.attributes.hdrTransfer) }
      is Watermark -> spec.toOverlay()
      is Text -> spec.toOverlay(capabilities, attributes)
      else -> null
    }
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
      rasterizeText(text, style, attributes.inputSize)
        ?: return EffectResolution.Unsupported(id, EMPTY_TEXT)
    val size = raster.pixelSize()

    return step { image, frame ->
      if (!frame.shows(visibleDuring)) {
        image
      } else {
        // Laid out once, at the size the export draws it, and scaled down for a preview rather
        // than re-laid. Laying out per target would wrap at the preview's frame width and the
        // export's separately, and the two could break lines on different words.
        val scale = frame.attributes.renderScale
        val drawn =
          Size(
            (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1),
          )
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

  private companion object {
    const val STRAIGHT_ANGLE = 180.0

    const val UNREADABLE_IMAGE =
      "The watermark image could not be decoded. Check that the path or URL is readable by this " +
        "process, and that the bytes are PNG, JPEG or HEIC."

    const val EMPTY_TEXT = "The text and its style leave nothing to draw."

    const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."
  }
}
