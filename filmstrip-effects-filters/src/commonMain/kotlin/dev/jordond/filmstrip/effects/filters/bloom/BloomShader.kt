package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterStep
import dev.jordond.filmstrip.effects.filters.internal.GLOW_BLURRED
import dev.jordond.filmstrip.effects.filters.internal.GLOW_FRAME
import dev.jordond.filmstrip.effects.filters.internal.GlowSteps

/**
 * The GLSL ES 3.00 fragment sources that draw a [Bloom], read by media3 and the browser, and the order they run in.
 *
 * Every figure comes from [BloomParams] as a uniform, so the shaders hold none of their own. A clip's frame holds
 * clear bars. Their alpha comes through untouched and they give the blur no light.
 */
@InternalFilmstripApi
public object BloomShader {
  /**
   * The chain that draws [bloom] the way [BloomSignal.bloomed] reads [BloomSignal.glow].
   *
   * The frame boxes down and takes [BloomSignal.response], the blur runs across and then up, and the last step
   * screens the blurred light over the frame and writes it.
   */
  public fun steps(bloom: Bloom): List<FilterStep> {
    val params = BloomParams.of(bloom)
    return GlowSteps.of(
      sigmaFraction = params.sigma,
      reads = FilterStep.INPUT,
      responseGlsl = RESPONSE_GLSL,
      responseUniforms =
        mapOf(
          "uThreshold" to floatArrayOf(params.threshold.toFloat()),
          "uKneeEnd" to floatArrayOf(params.kneeEnd.toFloat()),
        ),
      combineName = STEP_BLOOM,
      combineFragment = BLOOM_FRAGMENT,
      combineUniforms = mapOf("uGain" to floatArrayOf(params.gain.toFloat())),
    )
  }
}

private const val STEP_BLOOM = "bloom"

// BloomSignal.response in GLSL ES 3.00, for GlowSteps' response fragment. Each line carries the fragment sources' own
// two spaces.
private const val RESPONSE_GLSL =
  """
  uniform float uThreshold, uKneeEnd;
  vec3 glowResponse(vec3 boxed) {
    return boxed * smoothstep(uThreshold, uKneeEnd, lumaOf(boxed));
  }
  """

// BloomSignal.bloomed, the blurred light held at 1 and screened over the frame's codes.
private val BLOOM_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $GLOW_FRAME;
  uniform sampler2D $GLOW_BLURRED;
  uniform float uGain;

  void main() {
    vec4 texel = textureLod($GLOW_FRAME, vUv, 0.0);
    vec3 light = min(uGain * textureLod($GLOW_BLURRED, vUv, 0.0).rgb, vec3(1.0));
    fragColor = vec4(texel.rgb + light * (1.0 - texel.rgb), texel.a);
  }
  """.trimIndent()
}
