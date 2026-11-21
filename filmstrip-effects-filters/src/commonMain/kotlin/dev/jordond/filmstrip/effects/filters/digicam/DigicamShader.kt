package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterStep
import dev.jordond.filmstrip.effects.filters.internal.GLOW_BLURRED
import dev.jordond.filmstrip.effects.filters.internal.GLOW_FRAME
import dev.jordond.filmstrip.effects.filters.internal.GlowSteps
import dev.jordond.filmstrip.effects.filters.internal.HASH_GLSL
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.LUMA_GLSL
import dev.jordond.filmstrip.effects.filters.internal.glslFloat

/**
 * The GLSL ES 3.00 fragment sources that draw a [Digicam], read by media3 and the browser, and the order they run in.
 *
 * Every fixed number is written in from [DigicamSignal]'s constants, and everything a control or a frame changes is a
 * uniform a [FilterStep] hands back, so the shaders hold no figure of their own. A clip's frame holds clear bars.
 * Their alpha comes through untouched.
 */
@InternalFilmstripApi
public object DigicamShader {
  /**
   * The uniform that says `v` runs up the picture, 1 when it does and 0 when it runs down.
   *
   * It belongs to whoever runs the chain, and the noise reads it to count its cells from the top.
   */
  public const val FLIP_V: String = "uFlipV"

  /**
   * The chain that draws [digicam] the way [DigicamSignal.pixelAt] reads [DigicamSignal.blur].
   *
   * The frame is blurred across and then up at its own size, and the last step sharpens the frame against the blur,
   * clips it and adds the noise.
   */
  public fun steps(digicam: Digicam): List<FilterStep> {
    val params = DigicamParams.of(digicam)
    return GlowSteps.of(
      sigmaFraction = HALO_SIGMA_FRACTION,
      reads = FilterStep.INPUT,
      responseGlsl = RESPONSE_GLSL,
      responseUniforms = emptyMap(),
      combineName = STEP_DIGICAM,
      combineFragment = DIGICAM_FRAGMENT,
      combineUniforms =
        mapOf(
          "uSeed" to floatArrayOf((digicam.seed and HASH_MASK).toFloat()),
          "uHaloGain" to floatArrayOf(params.haloGain.toFloat()),
          "uClipGain" to floatArrayOf(params.clipGain.toFloat()),
          "uNoise" to floatArrayOf(params.noiseAmplitude.toFloat()),
        ),
      combineUniformsAt = { seconds, width, height ->
        mapOf(
          "uTick" to floatArrayOf((DigicamSignal.tickAt(seconds) and HASH_MASK).toFloat()),
          "uResolution" to floatArrayOf(width.toFloat(), height.toFloat()),
          "uCellPx" to floatArrayOf(DigicamSignal.cellPx(height).toFloat()),
        )
      },
    )
  }
}

private const val STEP_DIGICAM = "digicam"

// The blur reads the frame's own codes. Each line carries the fragment sources' own two spaces.
private const val RESPONSE_GLSL =
  """
  vec3 glowResponse(vec3 boxed) {
    return boxed;
  }
  """

// DigicamSignal.pixelAt, the frame sharpened against its blur, clipped and given the chroma noise of its cell.
private val DIGICAM_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $GLOW_FRAME;
  uniform sampler2D $GLOW_BLURRED;
  uniform vec2 uResolution;
  uniform float ${DigicamShader.FLIP_V};
  uniform float uSeed, uTick, uHaloGain, uClipGain, uNoise, uCellPx;
$LUMA_GLSL
$HASH_GLSL

  void main() {
    vec4 texel = textureLod($GLOW_FRAME, vUv, 0.0);
    vec3 blurred = textureLod($GLOW_BLURRED, vUv, 0.0).rgb;
    vec3 sharpened = clamp(texel.rgb + uHaloGain * (texel.rgb - blurred), 0.0, 1.0);
    vec3 pushed = min(sharpened * uClipGain, vec3(1.0));
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${DigicamShader.FLIP_V});
    uint side = uint(uCellPx + 0.5);
    uint column = uint(clamp(floor(vUv.x * uResolution.x), 0.0, uResolution.x - 1.0)) / side;
    uint row = uint(clamp(floor(fromTop * uResolution.y), 0.0, uResolution.y - 1.0)) / side;
    uint cell = row * ${NOISE_CELLS_ACROSS}u + column;
    uint tick = uint(uTick + 0.5);
    float left = 1.0 - lumaOf(pushed);
    float weight = uNoise * left * left;
    float moveBlue = ${glslFloat(CB_TO_BLUE)} * weight * (draw(${PASS_NOISE_CB}u, tick, cell) * 2.0 - 1.0);
    float moveRed = ${glslFloat(CR_TO_RED)} * weight * (draw(${PASS_NOISE_CR}u, tick, cell) * 2.0 - 1.0);
    float moveGreen = -(KR * moveRed + KB * moveBlue) / (1.0 - KR - KB);
    fragColor = vec4(clamp(pushed + vec3(moveRed, moveGreen, moveBlue), 0.0, 1.0), texel.a);
  }
  """.trimIndent()
}
