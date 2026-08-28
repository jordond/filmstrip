package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.ExecutionContext
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect

/**
 * Lowers the built-in catalogue onto WebGL pass declarations.
 *
 * Only the effects that are a texture-space transform or a scalar resolve here. Rotate and Scale
 * change the size of the render target rather than adding a pass, which makes them pipeline setup,
 * and no browser pipeline has landed to set up.
 */
public actual class BuiltInEffectResolver actual constructor(
  @Suppress("unused") private val context: PlatformContext,
) : EffectResolver {
  actual override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    context: ExecutionContext,
    attributes: Attributes,
  ): EffectResolution? {
    if (capabilities.api != RenderApi.WebGl) return null

    return when (spec) {
      is Crop -> textureMatrix(spec.retainedRect(attributes.inputSize))
      is CropRect -> textureMatrix(spec.rect)
      is Flip -> textureMatrix(spec.axis.matrix())
      is Brightness -> spec.toPass()
      is Rotate, is Scale -> EffectResolution.Unsupported(spec.id, RESIZING_PENDING)
      is Watermark, is Text -> EffectResolution.Unsupported(spec.id, OVERLAYS_PENDING)
      else -> null
    }
  }

  // The multiply goes in as authored, with no arm for a kept grade. The compositor renders into an
  // eight-bit canvas, so this backend never reports one, and the constant that says so is pinned by
  // a test in filmstrip-transform-webcodecs.
  private fun Brightness.toPass(): EffectResolution =
    resolved(WebGlPass(BRIGHTNESS_PROGRAM, mapOf(BRIGHTNESS to floatArrayOf(scale))))

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

  private companion object {
    const val TEXTURE_PROGRAM = "filmstrip.texture"
    const val BRIGHTNESS_PROGRAM = "filmstrip.brightness"
    const val TEXTURE_MATRIX = "uTexMatrix"
    const val BRIGHTNESS = "uBrightness"

    const val RESIZING_PENDING =
      "Rotate and Scale change the size of the render target rather than adding a pass, so they " +
        "are pipeline setup rather than a resolved effect. No browser pipeline has landed to set " +
        "up."

    const val OVERLAYS_PENDING =
      "Overlay effects rasterise text and images into the frame, which needs a canvas the resolver " +
        "does not have. Their browser lowering has not landed yet."
  }
}
