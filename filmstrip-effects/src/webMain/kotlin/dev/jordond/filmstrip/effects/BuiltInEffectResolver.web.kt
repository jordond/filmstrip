package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
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
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayDecode
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.OverlayImages
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.drawnTextSize
import dev.jordond.filmstrip.effects.overlay.frameWithin
import dev.jordond.filmstrip.effects.overlay.named
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.preloadOverlays
import dev.jordond.filmstrip.effects.overlay.rasterizeText
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.effects.overlay.runWithin
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import kotlin.time.Duration

/**
 * Lowers the built-in catalogue onto WebGL pass declarations.
 *
 * Only the effects that are a texture-space transform or a colour matrix resolve here. Rotate and
 * Scale would resize the render target rather than adding a pass, and the browser pipeline sizes
 * its compositor once from the output spec when export starts, with no path for a resolved effect
 * to resize it afterward.
 *
 * Overlays are rasterised here, text on the spot and images from the ones [preload] decoded, and
 * resolve to a raster the compositor draws where the effect says.
 */
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
  private val images = OverlayImages()

  /**
   * Decodes every overlay image in [composition] and waits for every face its text overlays name.
   *
   * A browser decodes images only asynchronously and [resolve] cannot wait, so this runs before a
   * composition is planned. An image left out of it resolves unsupported.
   */
  @InternalFilmstripApi
  public suspend fun preload(composition: EditComposition) {
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
      is KenBurns -> EffectResolution.Unsupported(spec.id, PAN_PENDING)
      is Rotate, is Scale -> EffectResolution.Unsupported(spec.id, RESIZING_PENDING)
      is ImageOverlay -> spec.toOverlay(attributes)
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
  private fun ImageOverlay.toOverlay(attributes: Attributes): EffectResolution {
    if (image is ImageSource.Path) return EffectResolution.Unsupported(id, "${image.named()} $PATH_UNREADABLE")
    val picture =
      when (val decoded = images[image]) {
        null -> return EffectResolution.Unsupported(id, "${image.named()} $NOT_PRELOADED")
        OverlayDecode.Undecodable -> return EffectResolution.Unsupported(id, "${image.named()} $UNDECODABLE")
        is OverlayDecode.Refused -> return EffectResolution.Unsupported(id, decoded.message)
        is OverlayDecode.Decoded -> decoded.picture
      }

    val drawAt = drawnBy(placedOn(attributes.inputSize, picture.size), attributes)
    val effect = PlatformEffect(WebGlOverlay(picture.bitmap, picture.size, drawAt, picture.colour, picture.samples))
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
  ): EffectResolution {
    if (!capabilities.has(RenderFeature.TextRendering)) return EffectResolution.Unsupported(id, NO_TEXT_RENDERING)
    val raster =
      rasterizeText(text, style, attributes.layoutSize) ?: return EffectResolution.Unsupported(id, EMPTY_TEXT)

    val placement = placedOn(attributes.drawnTextSize(raster.size))
    return overlay(WebGlOverlay(raster.canvas, raster.size, drawnBy(placement, attributes)))
  }

  private fun OverlayEffect.drawnBy(
    base: OverlayPlacement,
    attributes: Attributes,
  ): (Duration) -> WebGlOverlayDraw? {
    val run = runWithin(attributes.span)
    val frame = attributes.inputSize
    return { time -> frameWithin(run, time)?.let { WebGlOverlayDraw(base.animatedBy(it).rectOn(frame), it.opacity) } }
  }

  private fun overlay(overlay: WebGlOverlay): EffectResolution = EffectResolution.Resolved(PlatformEffect(overlay))

  private fun resolved(pass: WebGlPass): EffectResolution = EffectResolution.Resolved(PlatformEffect(pass))

  private fun textureMatrix(matrix: FloatArray): EffectResolution =
    resolved(WebGlPass(TEXTURE_PROGRAM, mapOf(TEXTURE_MATRIX to matrix)))

  // filmstrip measures from the top-left with +Y down. A GL texture measures from the bottom-left
  // with +Y up, so the retained rect's bottom edge becomes the sampling origin.
  private fun textureMatrix(rect: NormalizedRect): EffectResolution =
    textureMatrix(
      columnMajor(
        scaleU = rect.width,
        scaleV = rect.height,
        offsetU = rect.left,
        offsetV = 1f - rect.bottom,
      ),
    )

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

private const val RESIZING_PENDING =
  "Rotate and Scale change the size of the render target rather than adding a pass, so they " +
    "are pipeline setup rather than a resolved effect. The browser pipeline sizes its compositor " +
    "once from the output spec and has no path for a resolved effect to resize it afterward."

private const val PAN_PENDING =
  "A pan moves the region it shows on every frame, and a pass here carries one texture matrix " +
    "settled at resolve. A pass that moves it per frame has not landed on this backend yet."

private const val PATH_UNREADABLE =
  "was named by a file path, which a browser cannot read. Pass it as bytes or a URI instead."

private const val NOT_PRELOADED =
  "was not decoded ahead of planning. Call preload with the composition before planning it."

private const val UNDECODABLE = "could not be decoded as an image."

private const val NO_TEXT_RENDERING = "This device cannot rasterise text into a frame."

private const val EMPTY_TEXT = "The text overlay has nothing to draw."
