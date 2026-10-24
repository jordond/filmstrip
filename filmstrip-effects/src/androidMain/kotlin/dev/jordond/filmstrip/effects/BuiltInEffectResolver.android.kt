package dev.jordond.filmstrip.effects

import android.graphics.Bitmap
import androidx.media3.common.Effect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.color.ColorMatrixEffect
import dev.jordond.filmstrip.effects.color.HdrColorMatrixEffect
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.uniformScale
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.KenBurnsTransformation
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.DecoderLimit
import dev.jordond.filmstrip.effects.overlay.HalfFloatCurve
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.PictureRead
import dev.jordond.filmstrip.effects.overlay.RasterOverlay
import dev.jordond.filmstrip.effects.overlay.RefusedPicture
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.UnreadablePicture
import dev.jordond.filmstrip.effects.overlay.drawnTextSize
import dev.jordond.filmstrip.effects.overlay.halfFloatCurve
import dev.jordond.filmstrip.effects.overlay.media3OverlayResolution
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rasterFor
import dev.jordond.filmstrip.effects.overlay.rasterizeText
import dev.jordond.filmstrip.effects.overlay.read
import dev.jordond.filmstrip.effects.overlay.runWithin
import dev.jordond.filmstrip.effects.overlay.size
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import androidx.media3.effect.Crop as Media3Crop

/**
 * Lowers the built-in catalogue onto Media3's effect classes.
 */
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.OpenGlEs) return null
    val color = spec.toColorMatrix(attributes.hdrTransfer)
    if (color != null) return color

    return when (spec) {
      is Rotate -> resolved(ScaleAndRotateTransformation.Builder().setRotationDegrees(spec.degrees.toFloat()).build())
      is Flip -> resolved(spec.toMedia3())
      is Crop -> resolved(spec.retainedRect(attributes.inputSize).toMedia3())
      is CropRect -> resolved(spec.rect.toMedia3())
      is KenBurns -> spec.toTransformation(attributes)
      is Scale -> resolved(spec.toMedia3())
      is ImageOverlay -> spec.toOverlay(attributes)
      is TextOverlay -> spec.toOverlay(capabilities, attributes)
      else -> null
    }
  }

  // Which effects are one matrix is the shared helper's answer, so the catalogue can grow without
  // this listing the types again, brightness included.
  //
  // On SDR media3's own matrix multiplies the encoded signal, which is where the entries are
  // written. A kept grade reaches the shader as linear light, so the matrix goes through a pass of
  // its own that moves each channel into that signal and back around it.
  //
  // A matrix that scales all three channels alike is the exception on a grade: it commutes with the
  // transfer function, so it stays one of media3's own matrices with the gain moved into the domain
  // the frame is held in, and costs no pass of its own. Which lowering an effect takes is the
  // matrix's shape rather than the type that wrote it, so a folded run takes the same one a
  // Brightness does.
  private fun EffectSpec.toColorMatrix(transfer: HdrTransfer?): EffectResolution? {
    val matrix = colorMatrixOf(this) ?: return null
    val scale = matrix.uniformScale

    return resolved(
      when {
        scale != null -> scale.toRgbAdjustment(transfer)
        transfer == null -> ColorMatrixEffect(matrix)
        else -> HdrColorMatrixEffect(matrix, transfer)
      },
    )
  }

  // A region outside the frame samples nothing, and one with no area collapses the frame to a
  // point, so both are refused by name rather than drawn as whatever the reciprocal comes out as.
  private fun KenBurns.toTransformation(attributes: Attributes): EffectResolution {
    if (!from.isValid || !to.isValid) return unsupported(id, REGION_OUTSIDE_FRAME)
    return resolved(KenBurnsTransformation(this, attributes.span))
  }

  // The frame an overlay measures against is the one entering it, not the composition's output.
  // They are the same for a composition-scoped overlay. A clip-scoped one runs in that item's own
  // chain, before the size stage pins every clip to the output frame, so measuring it against the
  // output would size it against a frame it never sees.
  private fun ImageOverlay.toOverlay(attributes: Attributes): EffectResolution =
    toOverlay(image.read(FilmstripContext.get()), attributes)

  /**
   * Resolves this overlay onto a frame of [attributes] from its already [read] [picture], or refuses it when the
   * picture could not be read, was refused before or by Android's decoder, or was decoded into a space its samples
   * cannot be read back from.
   */
  internal fun ImageOverlay.toOverlay(
    picture: PictureRead,
    attributes: Attributes,
  ): EffectResolution {
    val decoded =
      when (picture) {
        UnreadablePicture -> return unsupported(id, UNREADABLE_IMAGE)
        is RefusedPicture -> return unsupported(id, picture.reason)
        is DecodedPicture -> picture
      }
    if (decoded.bitmap.halfFloatCurve() == HalfFloatCurve.Unknown) return unsupported(id, UNKNOWN_HALF_FLOAT_SPACE)
    val size = decoded.bitmap.size()
    val placement = placedOn(attributes.inputSize, size)
    return resolved(decoded.bitmap, this, placement, attributes, decoded.colour, decoded.limits)
  }

  private fun TextOverlay.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    if (!capabilities.has(RenderFeature.TextRendering)) return unsupported(id, NO_TEXT_RENDERING)
    val bitmap = rasterizeText(text, style, attributes.layoutSize) ?: return unsupported(id, EMPTY_TEXT)
    val size = bitmap.size()
    // Laid out against the frame an export writes and only resampled here, so a graph lowered
    // smaller than that breaks its lines on the same words. The two frames agree for an export,
    // which composites one to one.
    val drawn = attributes.drawnTextSize(size)
    return resolved(bitmap, this, placedOn(drawn), attributes)
  }

  private fun resolved(effect: Effect): EffectResolution = EffectResolution.Resolved(PlatformEffect(effect))

  // An overlay resolves to a TextureOverlay rather than an Effect: the backend collects a run of
  // them into one OverlayEffect, so N overlays cost one GL pass instead of N. What it reports keys on
  // the grade being written, not on what the device could write, so an SDR export on an HDR capable
  // device resolves cleanly.
  private fun resolved(
    bitmap: Bitmap,
    spec: OverlayEffect,
    placement: OverlayPlacement,
    attributes: Attributes,
    colour: PictureColour? = null,
    limits: List<DecoderLimit> = emptyList(),
  ): EffectResolution {
    val transfer = attributes.hdrTransfer
    val raster = bitmap.rasterFor(transfer, colour)
    // Derived here so the per-frame sampling reads a range rather than rebuilding one on the GL
    // thread for every frame of the export.
    val run = spec.runWithin(attributes.span)
    val overlay = RasterOverlay(raster.bitmap, spec, placement, bitmap.size(), run, raster.hdrLuminance)
    return media3OverlayResolution(PlatformEffect(overlay), transfer, limits)
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
  // scale multiplies the encoded signal, which is where it is defined. An HDR grade is processed in
  // linear light, and which linear is media3's own: PQ goes through its EOTF and comes out display
  // referred, while HLG gets only the inverse OETF and stays scene referred.
  //
  // Nothing clamps a scale on a grade the way the matrix pass does. The light has headroom above
  // white and the encoder cuts it off at the transfer's peak, which is where the pass clamps too.
  private fun Float.toRgbAdjustment(transfer: HdrTransfer?): Effect {
    val gain =
      when (transfer) {
        null -> this
        HdrTransfer.Pq -> brightnessDisplayGain(this)
        HdrTransfer.Hlg -> brightnessSceneGain(this)
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
}

private const val UNREADABLE_IMAGE =
  "The overlay image could not be read. Check that the path or Uri is readable by this process."

// Android decodes a sixteen-bit picture to half floats in extended sRGB or its linear form, and an overlay reads
// either back to the samples the file stores. A decoder that picks any other space is Android's to answer for, so the
// overlay is refused by name rather than drawn through a curve it does not have.
internal const val UNKNOWN_HALF_FLOAT_SPACE =
  "Android decoded the overlay image into a colour space other than extended sRGB, so its samples cannot be read " +
    "back as the file stores them."

private const val EMPTY_TEXT = "The text and its style leave nothing to draw."

private const val REGION_OUTSIDE_FRAME =
  "A pan travels between two regions of the frame, so both have to have area and lie inside it."

private const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."
