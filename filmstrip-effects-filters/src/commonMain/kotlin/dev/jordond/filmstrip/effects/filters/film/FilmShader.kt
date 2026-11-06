package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.HASH_GLSL
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import dev.jordond.filmstrip.geometry.Size

/**
 * The GLSL ES 3.00 fragment sources that draw a [Film], read by media3 and the browser, and the order they run in.
 *
 * Every fixed number is written in from [FilmSignal]'s constants, and everything a control or a frame changes is a
 * uniform a [FilmStep] hands back, so the shaders hold no figure of their own. Each reads at the `vUv` varying.
 *
 * A clip's frame holds clear bars. Their alpha comes through every step untouched and they give the blur no light, so
 * neither the glow nor the grain ever shows over the fill.
 */
@InternalFilmstripApi
public object FilmShader {
  /**
   * What a [FilmStep] names the frame the whole chain starts from in [FilmStep.reads].
   */
  public const val INPUT: String = "input"

  /**
   * The uniform that says `v` runs up the picture, 1 when it does and 0 when it runs down.
   *
   * It belongs to whoever runs the chain, and only the grain reads it.
   */
  public const val FLIP_V: String = "uFlipV"

  /**
   * The chain that draws [film], halation first and grain over its result, leaving out either at a strength of 0.
   *
   * Every step before the last runs on a target of its own, and the last one writes the frame.
   */
  public fun steps(film: Film): List<FilmStep> {
    val params = FilmParams.of(film)
    return buildList {
      if (params.halationGain > 0.0) addAll(halationSteps(params))
      if (params.grainStrength > 0.0) add(grainStep(params, if (isEmpty()) INPUT else STEP_HALATION))
    }
  }

  private fun halationSteps(params: FilmParams): List<FilmStep> =
    listOf(
      FilmStep(STEP_RESPONSE, RESPONSE_FRAGMENT, mapOf(FRAME to INPUT), ::blurredSize) { _, width, height ->
        val plan = FilmSignal.halationBlurPlan(width)
        mapOf(
          "uTargetSize" to floatArrayOf(plan.width.toFloat(), plan.heightOf(height).toFloat()),
          "uBox" to floatArrayOf(plan.factor.toFloat()),
        )
      },
      blurStep(STEP_BLUR_ACROSS, STEP_RESPONSE, horizontal = true),
      blurStep(STEP_BLUR_DOWN, STEP_BLUR_ACROSS, horizontal = false),
      FilmStep(
        STEP_HALATION,
        HALATION_FRAGMENT,
        mapOf(FRAME to INPUT, GLOW to STEP_BLUR_DOWN),
        ::frameSize,
      ) { _, _, _ -> mapOf("uGain" to floatArrayOf(params.halationGain.toFloat())) },
    )

  private fun blurStep(
    name: String,
    reads: String,
    horizontal: Boolean,
  ): FilmStep =
    FilmStep(name, BLUR_FRAGMENT, mapOf(FRAME to reads), ::blurredSize) { _, width, height ->
      val plan = FilmSignal.halationBlurPlan(width)
      val gap = 1f / plan.width
      val weights = FloatArray(WIDE_BLUR_MAX_TAPS + 1)
      plan.weights.forEachIndexed { index, weight -> weights[index] = weight.toFloat() }
      mapOf(
        "uGap" to if (horizontal) floatArrayOf(gap, 0f) else floatArrayOf(0f, gap * width / height),
        "uTaps" to floatArrayOf(plan.taps.toFloat()),
        "uWeights[0]" to weights,
      )
    }

  private fun grainStep(
    params: FilmParams,
    reads: String,
  ): FilmStep =
    FilmStep(STEP_GRAIN, GRAIN_FRAGMENT, mapOf(FRAME to reads), ::frameSize) { seconds, width, height ->
      mapOf(
        "uSeed" to floatArrayOf((GRAIN_SEED and HASH_MASK).toFloat()),
        "uTick" to floatArrayOf((FilmSignal.grainTickAt(seconds) and HASH_MASK).toFloat()),
        "uStrength" to floatArrayOf(params.grainStrength.toFloat()),
        "uResolution" to floatArrayOf(width.toFloat(), height.toFloat()),
        "uCellSpan" to floatArrayOf(FilmSignal.grainCellSpan(width, height).toFloat()),
      )
    }

  private fun blurredSize(
    width: Int,
    height: Int,
  ): Size {
    val plan = FilmSignal.halationBlurPlan(width)
    return Size(plan.width, plan.heightOf(height))
  }

  private fun frameSize(
    width: Int,
    height: Int,
  ): Size = Size(width, height)
}

/**
 * One draw of a [Film]'s chain.
 *
 * @property name What a later step calls this step's output in its own [reads].
 * @property fragment The fragment shader source.
 * @property reads Each sampler this step reads, to the [name] of an earlier step or [FilmShader.INPUT].
 * @property targetAt The size this step draws at, for a frame of a width and height in pixels.
 * @property uniformsAt The uniforms for a frame at a composition time in seconds and a width and height in pixels, by
 * name. A name ending in `[0]` is a float array, and any other is a float or a vec by its length.
 */
@InternalFilmstripApi
public class FilmStep internal constructor(
  public val name: String,
  public val fragment: String,
  public val reads: Map<String, String>,
  public val targetAt: (width: Int, height: Int) -> Size,
  public val uniformsAt: (seconds: Double, width: Int, height: Int) -> Map<String, FloatArray>,
)

private const val FRAME = "uFrame"
private const val GLOW = "uGlow"

private const val STEP_RESPONSE = "response"
private const val STEP_BLUR_ACROSS = "blurAcross"
private const val STEP_BLUR_DOWN = "blurDown"
private const val STEP_HALATION = "halation"
private const val STEP_GRAIN = "grain"

private val LUMA_GLSL =
  """
  const float KR = ${glslFloat(KR)};
  const float KB = ${glslFloat(KB)};
  float lumaOf(vec3 c) { return KR * c.r + (1.0 - KR - KB) * c.g + KB * c.b; }
  """

// Boxes the frame's codes down to one blurred pixel, the area under it weighted by alpha so a bar gives no light, and
// reads the response of the result.
private val RESPONSE_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uTargetSize;
  uniform float uBox;
$LUMA_GLSL

  void main() {
    int box = int(uBox + 0.5);
    vec3 sum = vec3(0.0);
    for (int y = 0; y < box; y++) {
      for (int x = 0; x < box; x++) {
        vec2 tap = (vec2(float(x), float(y)) + 0.5) / uBox - 0.5;
        vec4 texel = textureLod($FRAME, vUv + tap / uTargetSize, 0.0);
        sum += texel.rgb * texel.a;
      }
    }
    float response = smoothstep(${glslFloat(HALATION_LOW)}, ${glslFloat(HALATION_HIGH)}, lumaOf(sum / (uBox * uBox)));
    fragColor = vec4(
      response * vec3(${glslFloat(
    HALATION_TINT_RED,
  )}, ${glslFloat(HALATION_TINT_GREEN)}, ${glslFloat(HALATION_TINT_BLUE)}),
      1.0
    );
  }
  """.trimIndent()
}

private val BLUR_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uGap;
  uniform float uTaps;
  uniform float uWeights[${WIDE_BLUR_MAX_TAPS + 1}];

  void main() {
    vec3 sum = uWeights[0] * textureLod($FRAME, vUv, 0.0).rgb;
    int taps = int(uTaps + 0.5);
    for (int i = 1; i <= taps; i++) {
      vec2 offset = float(i) * uGap;
      sum += uWeights[i] * (textureLod($FRAME, vUv + offset, 0.0).rgb + textureLod($FRAME, vUv - offset, 0.0).rgb);
    }
    fragColor = vec4(sum, 1.0);
  }
  """.trimIndent()
}

private val HALATION_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform sampler2D $GLOW;
  uniform float uGain;

  void main() {
    vec4 texel = textureLod($FRAME, vUv, 0.0);
    fragColor = vec4(min(texel.rgb + uGain * textureLod($GLOW, vUv, 0.0).rgb, vec3(1.0)), texel.a);
  }
  """.trimIndent()
}

private val GRAIN_FRAGMENT: String by lazy {
  """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uResolution;
  uniform float ${FilmShader.FLIP_V};
  uniform float uSeed, uTick, uStrength, uCellSpan;
$LUMA_GLSL
$HASH_GLSL

  void main() {
    vec4 texel = textureLod($FRAME, vUv, 0.0);
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${FilmShader.FLIP_V});
    uint span = uint(uCellSpan + 0.5);
    uint column = uint(clamp(floor(vUv.x * uResolution.x), 0.0, uResolution.x - 1.0)) * ${GRAIN_CELL_SCALE}u / span;
    uint row = uint(clamp(floor(fromTop * uResolution.y), 0.0, uResolution.y - 1.0)) * ${GRAIN_CELL_SCALE}u / span;
    uint cell = row * ${GRAIN_CELLS_ACROSS}u + column;
    uint tick = uint(uTick + 0.5);
    float grain = draw(${PASS_GRAIN_FIRST}u, tick, cell) + draw(${PASS_GRAIN_SECOND}u, tick, cell) - 1.0;
    float luma = lumaOf(texel.rgb);
    float weight = max(${glslFloat(GRAIN_WEIGHT_FLOOR)}, ${glslFloat(GRAIN_WEIGHT_PEAK)} * luma * (1.0 - luma));
    fragColor = vec4(clamp(texel.rgb + uStrength * weight * grain, 0.0, 1.0), texel.a);
  }
  """.trimIndent()
}
