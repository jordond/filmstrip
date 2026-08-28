package dev.jordond.filmstrip.effects

import androidx.media3.common.Effect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.ExecutionContext
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import androidx.media3.effect.Crop as Media3Crop

/**
 * Lowers the built-in catalogue onto Media3's effect classes.
 */
@OptIn(InternalFilmstripApi::class)
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.OpenGlEs) return null

    return when (spec) {
      is Rotate -> resolved(ScaleAndRotateTransformation.Builder().setRotationDegrees(spec.degrees.toFloat()).build())
      is Flip -> resolved(spec.toMedia3())
      is Crop -> resolved(spec.retainedRect(attributes.inputSize).toMedia3())
      is CropRect -> resolved(spec.rect.toMedia3())
      is Scale -> resolved(spec.toMedia3())
      is Brightness -> resolved(spec.toMedia3(attributes.hdrTransfer))
      is Watermark -> spec.toOverlay(capabilities, attributes)
      is Text -> spec.toOverlay(capabilities, attributes)
      else -> null
    }
  }

  // The frame an overlay measures against is the one entering it, not the composition's output.
  // They are the same for a composition-scoped overlay. A clip-scoped one runs in that item's own
  // chain, before the size stage pins every clip to the output frame, so measuring it against the
  // output would size it against a frame it never sees.
  private fun Watermark.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    val bitmap = image.decode(FilmstripContext.get()) ?: return unsupported(id, UNREADABLE_IMAGE)
    val size = bitmap.size()
    val placement = placedOn(attributes.inputSize, size)
    return resolved(RasterOverlay(bitmap, placement.toOverlaySettings(size, opacity), visibleDuring), capabilities)
  }

  private fun Text.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    if (!capabilities.has(RenderFeature.TextRendering)) return unsupported(id, NO_TEXT_RENDERING)
    val bitmap = rasterizeText(text, style, attributes.inputSize) ?: return unsupported(id, EMPTY_TEXT)
    val size = bitmap.size()
    // Rasterised at the size it will occupy, so it is composited one to one and never resampled.
    return resolved(RasterOverlay(bitmap, placedOn(size).toOverlaySettings(size, 1f), visibleDuring), capabilities)
  }

  private fun resolved(effect: Effect): EffectResolution = EffectResolution.Resolved(PlatformEffect(effect))

  // An overlay resolves to a TextureOverlay rather than an Effect: the backend collects a run of
  // them into one OverlayEffect, so N overlays cost one GL pass instead of N.
  private fun resolved(
    overlay: RasterOverlay,
    capabilities: RenderCapabilities,
  ): EffectResolution {
    val effect = PlatformEffect(overlay)
    if (!capabilities.supportsHdr) return EffectResolution.Resolved(effect)
    return EffectResolution.Degraded(effect, DegradationReason.ColorSpaceConverted, WIDE_GAMUT_OVERLAY)
  }

  private fun unsupported(
    id: String,
    message: String,
  ): EffectResolution = EffectResolution.Unsupported(id, message)

  // Mirroring goes through a negative scale, which media3 leaves unspecified, so both axes are
  // checked against the pixels a real export writes rather than against the effect it resolves to.
  private fun Flip.toMedia3(): Effect =
    ScaleAndRotateTransformation
      .Builder()
      .apply {
        when (axis) {
          FlipAxis.Horizontal -> setScale(-1f, 1f)
          FlipAxis.Vertical -> setScale(1f, -1f)
        }
      }.build()

  // media3 keeps the input's own transfer function as its SDR working colour space, so on SDR the
  // matrix multiplies the encoded signal, which is where Brightness is defined. An HDR grade is
  // processed in linear light, and which linear is media3's own: PQ goes through its EOTF and comes
  // out display referred, while HLG gets only the inverse OETF and stays scene referred.
  private fun Brightness.toMedia3(transfer: HdrTransfer?): Effect {
    val gain =
      when (transfer) {
        null -> scale
        HdrTransfer.Pq -> brightnessDisplayGain(scale)
        HdrTransfer.Hlg -> brightnessSceneGain(scale)
      }

    return RgbAdjustment
      .Builder()
      .setRedScale(gain)
      .setGreenScale(gain)
      .setBlueScale(gain)
      .build()
  }

  // Height and aspect are all a scale decides on its own. How a frame whose aspect does not match
  // the output gets laid into it belongs to the output frame, so the pipeline pins that once from
  // Scale.fit rather than having every scale carry a layout of its own.
  private fun Scale.toMedia3(): Effect = Presentation.createForHeight(targetHeight)

  // filmstrip uses `0..1` from the top-left with +Y down. Media3 uses `[-1, 1]` from the centre
  // with +Y up, and takes its arguments as (left, right, bottom, top).
  private fun NormalizedRect.toMedia3(): Effect =
    Media3Crop(
      // left =
      2f * left - 1f,
      // right =
      2f * right - 1f,
      // bottom =
      1f - 2f * bottom,
      // top =
      1f - 2f * top,
    )

  private companion object {
    const val UNREADABLE_IMAGE =
      "The watermark image could not be decoded. Check that the path or Uri is readable by this " +
        "process, and that the bytes are PNG, JPEG or WebP."

    const val EMPTY_TEXT = "The text and its style leave nothing to draw."

    const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."

    const val WIDE_GAMUT_OVERLAY =
      "An overlay on an HDR grade keeps its sRGB values, which media3 reads as BT.2020 without " +
        "converting the primaries. Neutral tones are unaffected and saturated ones lose some " +
        "saturation."
  }
}
