@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effect.WebGlOverlayDraw
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.RgbAdjustment
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.toColumnMajor4x4
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.regionAt
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayDecode
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.OverlayImages
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.WEBGL
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.drawnTextSize
import dev.jordond.filmstrip.effects.overlay.fitsTextureSide
import dev.jordond.filmstrip.effects.overlay.frameWithin
import dev.jordond.filmstrip.effects.overlay.named
import dev.jordond.filmstrip.effects.overlay.picturePastTextureSide
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.preloadOverlays
import dev.jordond.filmstrip.effects.overlay.rasterizeText
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.resolvedFrom
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration

/**
 * Lowers the built-in catalogue onto WebGL pass declarations.
 *
 * Only the effects that are a texture-space transform or a color matrix resolve here. A rotation is a texture matrix
 * about the frame's centre, and a scale resolves to a pass that samples the frame unchanged, since the output size it
 * sets is negotiated before the compositor is built. A pan is a texture matrix the compositor asks for again on every
 * frame, at the region [regionAt] gives for that frame's composition time.
 *
 * Overlays are rasterised here, text on the spot and images from the ones [prepare] decoded, and
 * resolve to a raster the compositor draws where the effect says. Text carries its straight sixteen-bit samples, and
 * text too large to draw is refused with the reason its raster gives. Text or a picture longer on a side than the
 * capabilities' `maxTextureSize` is refused, since WebGL uploads nothing past it.
 */
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  private val images = OverlayImages()

  /**
   * Decodes every overlay image in [composition] and waits for every face its text overlays name.
   *
   * A browser decodes images only asynchronously and [resolve] cannot wait, so this runs before a
   * composition is planned. An image left out of it resolves unsupported.
   */
  override suspend fun prepare(composition: EditComposition) {
    preloadOverlays(composition, images)
  }

  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.WebGl) return null

    return when (spec) {
      is Crop -> textureMatrix(spec.retainedRect(attributes.inputSize))
      is CropRect -> textureMatrix(spec.rect)
      is Flip -> textureMatrix(spec.axis.matrix())
      is Brightness,
      is RgbAdjustment,
      is Contrast,
      is Saturation,
      is HueRotate,
      is Sepia,
      is Invert,
      is ColorMatrix,
      -> spec.toColorPass()
      is KenBurns -> spec.toPass(attributes)
      is Rotate -> spec.toPass()
      is Scale -> textureMatrix(IDENTITY)
      is ImageOverlay -> spec.toOverlay(capabilities, attributes)
      is TextOverlay -> spec.toOverlay(capabilities, attributes)
      else -> null
    }
  }

  // The matrix goes in as authored, in the encoded domain it was written against. A compositor
  // rendering into an eight-bit canvas multiplies it straight into the sample, and one keeping a
  // grade runs the shared arm of HDR_COLOR_MATRIX_GLSL around it, which moves the light into that
  // same domain and back. Either way the entries the pass carries are the ones the caller wrote.
  private fun EffectSpec.toColorPass(): EffectResolution {
    val matrix = checkNotNull(colorMatrixOf(this)).toColumnMajor4x4()

    return resolved(WebGlPass(COLOR_MATRIX_PROGRAM, mapOf(COLOR_MATRIX to matrix)))
  }

  // An overlay measures against the frame entering it, which is the clip's for a clip overlay and
  // the output's for a composition one.
  private fun ImageOverlay.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution {
    if (image is ImageSource.Path) return EffectResolution.Unsupported(id, "${image.named()} $PATH_UNREADABLE")
    val picture =
      when (val decoded = images[image]) {
        null -> return EffectResolution.Unsupported(id, "${image.named()} $NOT_PRELOADED")
        OverlayDecode.Undecodable -> return EffectResolution.Unsupported(id, "${image.named()} $UNDECODABLE")
        is OverlayDecode.Refused -> return EffectResolution.Unsupported(id, decoded.message)
        is OverlayDecode.Decoded -> decoded.picture
      }
    val shown = picture.shownSize
    if (!fitsTextureSide(shown.width, shown.height, capabilities.maxTextureSize)) {
      val reason = picturePastTextureSide(shown.width, shown.height, capabilities.maxTextureSize, WEBGL)
      return EffectResolution.Unsupported(id, reason)
    }

    val drawAt = drawnBy(placedOn(attributes.inputSize, shown), attributes)
    val source = OverlaySource.Image(picture.bitmap, picture.samples)
    val effect = PlatformEffect(WebGlOverlay(source, picture.size, drawAt, picture.color, picture.orientation))
    return if (picture.limits.isEmpty()) {
      EffectResolution.Resolved(effect)
    } else {
      EffectResolution.Degraded(effect, picture.limits)
    }
  }

  // Laid out against the frame an export writes and only resampled to the frame entering it, so a
  // preview breaks its lines on the same words.
  private fun TextOverlay.toOverlay(
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution =
    resolvedFrom(
      capabilities,
      { rasterizeText(text, style, attributes.layoutSize, capabilities.maxTextureSize) },
    ) { raster ->
      val placement = placedOn(attributes.drawnTextSize(raster.size))
      val drawAt = drawnBy(placement, attributes)
      overlay(WebGlOverlay(OverlaySource.Samples(raster.samples), raster.size, drawAt))
    }

  private fun OverlayEffect.drawnBy(
    base: OverlayPlacement,
    attributes: Attributes,
  ): (Duration) -> WebGlOverlayDraw? {
    val drawnRange = drawnRangeWithin(attributes.span)
    val frame = attributes.inputSize
    return { time ->
      frameWithin(drawnRange, time)?.let { WebGlOverlayDraw(base.animatedBy(it).rectOn(frame), it.opacity) }
    }
  }

  private fun overlay(overlay: WebGlOverlay): EffectResolution = EffectResolution.Resolved(PlatformEffect(overlay))

  private fun resolved(pass: WebGlPass): EffectResolution = EffectResolution.Resolved(PlatformEffect(pass))

  private fun textureMatrix(matrix: FloatArray): EffectResolution =
    resolved(WebGlPass(TEXTURE_PROGRAM, mapOf(TEXTURE_MATRIX to matrix)))

  private fun textureMatrix(rect: NormalizedRect): EffectResolution = textureMatrix(rect.matrix())

  private fun KenBurns.toPass(attributes: Attributes): EffectResolution {
    val span = attributes.span
    return resolved(WebGlPass(TEXTURE_PROGRAM) { time -> regionAt(time, span).matrix() })
  }

  // filmstrip measures from the top-left with +Y down. A GL texture measures from the bottom-left
  // with +Y up, so the retained rect's bottom edge becomes the sampling origin.
  private fun NormalizedRect.matrix(): FloatArray =
    columnMajor(
      scaleU = width,
      scaleV = height,
      offsetU = left,
      offsetV = 1f - bottom,
    )

  // A quarter turn swaps the frame's sides, so the output's u runs along the source's v. Each turn is a permutation of
  // the unit square, read back from the output to the source, which is the inverse of the counter-clockwise turn.
  private fun Rotate.toPass(): EffectResolution {
    if (degrees.mod(QUARTER_TURN) != 0) return EffectResolution.Unsupported(id, OFF_QUARTER_TURN)
    return when (degrees.mod(FULL_TURN) / QUARTER_TURN) {
      0 -> textureMatrix(IDENTITY)
      1 -> textureMatrix(floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f))
      2 -> textureMatrix(floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, 1f))
      else -> textureMatrix(floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, 1f))
    }
  }

  private fun FlipAxis.matrix(): FloatArray =
    when (this) {
      FlipAxis.Horizontal -> columnMajor(scaleU = -1f, scaleV = 1f, offsetU = 1f, offsetV = 0f)
      FlipAxis.Vertical -> columnMajor(scaleU = 1f, scaleV = -1f, offsetU = 0f, offsetV = 1f)
    }

  // A GLSL mat3 is column-major, and the third column carries the translation.
  private fun columnMajor(
    scaleU: Float,
    scaleV: Float,
    offsetU: Float,
    offsetV: Float,
  ): FloatArray =
    floatArrayOf(
      scaleU,
      0f,
      0f,
      0f,
      scaleV,
      0f,
      offsetU,
      offsetV,
      1f,
    )
}

private const val TEXTURE_PROGRAM = "filmstrip.texture"
private const val COLOR_MATRIX_PROGRAM = "filmstrip.colorMatrix"
private const val TEXTURE_MATRIX = "uTexMatrix"
private const val COLOR_MATRIX = "uColorMatrix"

private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
private const val QUARTER_TURN = 90
private const val FULL_TURN = 360

private const val OFF_QUARTER_TURN =
  "The browser turns a frame by whole quarter turns only, 0, 90, 180 or 270 degrees, which are the angles a rotation " +
    "takes."

private const val PATH_UNREADABLE =
  "was named by a file path, which a browser cannot read. Pass it as bytes or a URI instead."

private const val NOT_PRELOADED =
  "was not decoded ahead of planning. Call prepare with the composition before planning it."

private const val UNDECODABLE = "could not be decoded as an image."
