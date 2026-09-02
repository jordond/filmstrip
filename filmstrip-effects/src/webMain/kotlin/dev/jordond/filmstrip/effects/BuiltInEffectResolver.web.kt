package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
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
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * Lowers the built-in catalogue onto WebGL pass declarations.
 *
 * Only the effects that are a texture-space transform or a colour matrix resolve here. Rotate and
 * Scale change the size of the render target rather than adding a pass, which makes them pipeline
 * setup, and no browser pipeline has landed to set up.
 */
@OptIn(ExperimentalFilmstripApi::class, InternalFilmstripApi::class)
public actual class BuiltInEffectResolver actual constructor() : EffectResolver {
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
      -> spec.toColorPass(attributes.hdrTransfer)
      is KenBurns -> EffectResolution.Unsupported(spec.id, PAN_PENDING)
      is Rotate, is Scale -> EffectResolution.Unsupported(spec.id, RESIZING_PENDING)
      is ImageOverlay, is TextOverlay -> EffectResolution.Unsupported(spec.id, OVERLAYS_PENDING)
      else -> null
    }
  }

  // The matrix goes in as authored, in the encoded domain a compositor rendering into an eight-bit
  // canvas hands it. A kept grade is refused rather than lowered, since the pass would then hold
  // linear light and need the arm HdrColorMatrixEffect runs on media3. The compositor reports no
  // ten-bit output today, so nothing reaches this refusal, and it stands whatever a later pipeline
  // reports rather than resting on that.
  private fun EffectSpec.toColorPass(transfer: HdrTransfer?): EffectResolution {
    if (transfer != null) return EffectResolution.Unsupported(id, GRADE_PENDING)
    val matrix = checkNotNull(colorMatrixOf(this)).toColumnMajor4x4()

    return resolved(WebGlPass(COLOR_MATRIX_PROGRAM, mapOf(COLOR_MATRIX to matrix)))
  }

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

private const val GRADE_PENDING =
  "A colour matrix on an export that keeps its HDR grade runs on light rather than on the encoded " +
    "signal, and this backend has no pass for that domain yet. Export to SDR, or drop the effect."

private const val RESIZING_PENDING =
  "Rotate and Scale change the size of the render target rather than adding a pass, so they " +
    "are pipeline setup rather than a resolved effect. No browser pipeline has landed to set " +
    "up."

private const val PAN_PENDING =
  "A pan moves the region it shows on every frame, and a pass here carries one texture matrix " +
    "settled at resolve. The per-frame form has not landed yet."

private const val OVERLAYS_PENDING =
  "Overlay effects rasterise text and images into the frame, which needs a canvas the resolver " +
    "does not have. Their browser lowering has not landed yet."
