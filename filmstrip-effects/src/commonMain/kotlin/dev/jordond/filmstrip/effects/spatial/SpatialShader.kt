package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.VIGNETTE_GAIN_GLSL
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.color.IDENTITY_GRADE_LIGHT_GLSL
import dev.jordond.filmstrip.geometry.Size

// The GLSL ES 3.00 fragment sources a blur and a vignette draw with on the GPU backends, and the order they run in.
//
// Every figure a frame or a control changes is a uniform a step hands back, so the shaders hold no number of their
// own. Each reads at the vUv varying, and every texture it reads clamps at its edge.
//
// Both run on encoded codes. A backend holding the light of a kept grade passes the figures that move it to the SDR
// signal it encodes to, and the steps move it there on the way in, clamp at the grade's ceiling and take it back on the
// way out. A backend holding codes passes null. A backend holding light other than the light its grade reads passes
// the GLSL that carries it there and back, the way displayLightGradeGlsl does.
//
// Both read and write straight color. The blur weighs each pixel by its alpha and divides by the blurred alpha, so a
// picture keeps its level beside pixels with none, and every pixel keeps the alpha it came with.
internal object SpatialShader {
  const val INPUT: String = "input"

  fun blurSteps(
    blur: GaussianBlur,
    domain: HdrColorMatrixUniforms?,
    gradeLight: String = IDENTITY_GRADE_LIGHT_GLSL,
  ): List<SpatialStep> {
    val sigma = blur.sigma.toDouble()
    val signal = signalFigures(domain)
    val source = signalSource(gradeLight)
    return listOf(
      SpatialStep(
        STEP_DOWN,
        downFragment(source),
        mapOf(FRAME to INPUT),
        { width, height -> SpatialBlur.blurredSize(sigma, width, height) },
      ) { width, height ->
        val plan = BlurPlan.of(sigma, width)
        mapOf(
          "uTargetSize" to floatArrayOf(plan.width.toFloat(), plan.heightOf(height).toFloat()),
          "uBox" to floatArrayOf(plan.factor.toFloat()),
        ) + signal
      },
    ) + SpatialBlur.gaussianSteps(sigma, STEP_DOWN) +
      SpatialStep(
        STEP_UP,
        upFragment(source),
        mapOf(FRAME to SpatialBlur.BLURRED, ORIGINAL to INPUT),
        ::frameSize,
      ) { _, _ -> signal }
  }

  fun vignetteSteps(
    vignette: Vignette,
    domain: HdrColorMatrixUniforms?,
    gradeLight: String = IDENTITY_GRADE_LIGHT_GLSL,
  ): List<SpatialStep> {
    val signal = signalFigures(domain)
    return listOf(
      SpatialStep(
        STEP_VIGNETTE,
        vignetteFragment(signalSource(gradeLight)),
        mapOf(FRAME to INPUT),
        ::frameSize,
      ) { width, height ->
        mapOf(
          "uAspect" to floatArrayOf(width.toFloat() / height),
          "uStrength" to floatArrayOf(vignette.strength),
          "uRadius" to floatArrayOf(vignette.radius),
          "uSoftness" to floatArrayOf(vignette.softness),
        ) + signal
      },
    )
  }

  private fun frameSize(
    width: Int,
    height: Int,
  ): Size = Size(width, height)
}

/**
 * One draw of a blur or a vignette, or of the blur another effect runs through [SpatialBlur].
 *
 * @property name What a later step calls this step's output in its own [reads].
 * @property fragment The GLSL ES 3.00 fragment shader source, which reads at the `vUv` varying.
 * @property reads Each sampler this step reads, to the [name] of an earlier step or the frame the chain starts from.
 * @property targetAt The size this step draws at, for a frame of a width and height in pixels.
 * @property uniformsAt The uniforms for a frame of a width and height in pixels, by name. A name ending in `[0]` is a
 * float array, and any other is a float or a vec by its length.
 */
@InternalFilmstripApi
public class SpatialStep internal constructor(
  public val name: String,
  public val fragment: String,
  public val reads: Map<String, String>,
  public val targetAt: (width: Int, height: Int) -> Size,
  public val uniformsAt: (width: Int, height: Int) -> Map<String, FloatArray>,
)

/**
 * The parts of the GPU blur every effect that blurs a frame shares, so they all blur by [BlurPlan.of]'s kernel.
 *
 * An effect boxes its frame down to [blurredSize] with [BOX_DOWN_GLSL], then runs [gaussianSteps] over the result and
 * reads [BLURRED] back at its own size, filtering between the blurred pixels.
 */
@InternalFilmstripApi
public object SpatialBlur {
  /**
   * The name of the last step [gaussianSteps] runs, which holds the blurred frame.
   */
  public const val BLURRED: String = STEP_UPWARD

  /**
   * GLSL that boxes a frame down to one blurred pixel per factor by factor area.
   *
   * It defines `vec4 boxedDown(ivec2 size, vec2 uv, vec2 targetSize, float factor)`, the mean at `uv` of factor by
   * factor taps spread evenly over the frame pixels one blurred pixel covers, for a frame `size` pixels and a target
   * `targetSize`. Each tap reads the four texels around it and weighs them linearly. The shader including it defines
   * `vec4 premultipliedAt(ivec2 texel, ivec2 size)` first, which reads a texel held at the frame's edge in whatever
   * form the blur averages.
   */
  public val BOX_DOWN_GLSL: String =
    """
    vec4 boxedDown(ivec2 size, vec2 uv, vec2 targetSize, float factor) {
      vec2 share = vec2(size) / targetSize;
      vec2 cell = uv * targetSize - 0.5;
      int box = int(factor + 0.5);
      vec4 sum = vec4(0.0);
      for (int y = 0; y < box; y++) {
        for (int x = 0; x < box; x++) {
          vec2 at = (cell + (vec2(float(x), float(y)) + 0.5) / factor) * share - 0.5;
          vec2 corner = floor(at);
          vec2 t = at - corner;
          ivec2 low = ivec2(corner);
          vec4 top = mix(premultipliedAt(low, size), premultipliedAt(low + ivec2(1, 0), size), t.x);
          vec4 bottom = mix(premultipliedAt(low + ivec2(0, 1), size), premultipliedAt(low + ivec2(1, 1), size), t.x);
          sum += mix(top, bottom, t.y);
        }
      }
      return sum / (factor * factor);
    }
    """.trimIndent()

  /**
   * GLSL that moves a channel between the light a frame holds and the SDR signal it encodes to.
   *
   * It defines `vec3 toSignal(vec3 light)` and `vec3 fromSignal(vec3 signal)`, which clamps at the grade's ceiling, and
   * the uniforms [signalUniforms] fills. [gradeLight] carries the light the frame holds into the light the grade reads,
   * the way `displayLightGradeGlsl` does.
   */
  public fun signalGlsl(gradeLight: String = IDENTITY_GRADE_LIGHT_GLSL): String = signalSource(gradeLight)

  /**
   * The uniforms [signalGlsl] reads for a frame holding the light of [domain], or codes when it is null.
   */
  public fun signalUniforms(domain: HdrColorMatrixUniforms?): Map<String, FloatArray> = signalFigures(domain)

  /**
   * The size a blur of [sigmaFraction] of the frame's width runs at, for a frame [width] by [height].
   */
  public fun blurredSize(
    sigmaFraction: Double,
    width: Int,
    height: Int,
  ): Size {
    val plan = BlurPlan.of(sigmaFraction, width)
    return Size(plan.width, plan.heightOf(height))
  }

  /**
   * The two passes of [BlurPlan.of]'s kernel for [sigmaFraction], across and then up the frame, over the boxed frame
   * the step named [reads] drew.
   *
   * Each tap is one blurred pixel from the last, on either axis, and every channel is blurred.
   */
  public fun gaussianSteps(
    sigmaFraction: Double,
    reads: String,
  ): List<SpatialStep> =
    listOf(
      gaussianStep(STEP_ACROSS, reads, sigmaFraction, horizontal = true),
      gaussianStep(STEP_UPWARD, STEP_ACROSS, sigmaFraction, horizontal = false),
    )

  private fun gaussianStep(
    name: String,
    reads: String,
    sigmaFraction: Double,
    horizontal: Boolean,
  ): SpatialStep =
    SpatialStep(
      name,
      BLUR_FRAGMENT,
      mapOf(FRAME to reads),
      { width, height -> blurredSize(sigmaFraction, width, height) },
    ) { width, height -> gaussianUniforms(BlurPlan.of(sigmaFraction, width), height, horizontal) }

  /**
   * The GLSL ES 3.00 fragment shader one pass of a plan's kernel draws with, reading at the `vUv` varying.
   *
   * It reads the boxed frame from the `uFrame` sampler and takes its figures from [gaussianUniforms]. Every channel is
   * blurred.
   */
  public val GAUSSIAN_GLSL: String get() = BLUR_FRAGMENT

  /**
   * The uniforms [GAUSSIAN_GLSL] runs [plan]'s kernel with, across the boxed frame when [horizontal] is set and up it
   * otherwise, for a frame [heightPx] tall.
   *
   * A name ending in `[0]` is a float array, and any other is a float or a vec by its length.
   */
  public fun gaussianUniforms(
    plan: BlurPlan,
    heightPx: Int,
    horizontal: Boolean,
  ): Map<String, FloatArray> {
    val weights = FloatArray(BlurPlan.MAX_TAPS + 1)
    plan.weights.forEachIndexed { index, weight -> weights[index] = weight.toFloat() }
    return mapOf(
      "uGap" to if (horizontal) floatArrayOf(1f / plan.width, 0f) else floatArrayOf(0f, 1f / plan.heightOf(heightPx)),
      "uTaps" to floatArrayOf(plan.taps.toFloat()),
      "uWeights[0]" to weights,
    )
  }
}

private const val FRAME = "uFrame"
private const val WHITE = "uWhite"
private const val DISPLAY_GAMMA = "uDisplayGamma"
private const val OOTF_GAMMA = "uOotfGamma"
private const val CEILING = "uCeiling"
private const val ORIGINAL = "uOriginal"

// Codes are already the signal, so they go through with every power at one and clamp at white.
private fun signalFigures(domain: HdrColorMatrixUniforms?): Map<String, FloatArray> =
  mapOf(
    WHITE to floatArrayOf(domain?.white ?: 1f),
    DISPLAY_GAMMA to floatArrayOf(domain?.displayGamma ?: 1f),
    OOTF_GAMMA to floatArrayOf(domain?.ootfGamma ?: 1f),
    CEILING to floatArrayOf(domain?.ceiling ?: 1f),
  )

// Moves a channel between the light a backend holds and the SDR signal, the way HDR_COLOR_MATRIX_GLSL does.
private fun signalSource(gradeLight: String): String =
  """
  $gradeLight
  uniform float $WHITE, $DISPLAY_GAMMA, $OOTF_GAMMA, $CEILING;

  vec3 toSignal(vec3 light) {
    vec3 display = pow(max(toGradeLight(light), 0.0), vec3($OOTF_GAMMA));
    return pow(display / $WHITE, vec3(1.0 / $DISPLAY_GAMMA));
  }

  vec3 fromSignal(vec3 signal) {
    vec3 lit = pow(clamp(signal, 0.0, $CEILING), vec3($DISPLAY_GAMMA)) * $WHITE;
    return fromGradeLight(pow(lit, vec3(1.0 / $OOTF_GAMMA)));
  }
  """.trimIndent()

private const val STEP_DOWN = "blurDown"
private const val STEP_ACROSS = "blurAcross"
private const val STEP_UPWARD = "blurUpward"
private const val STEP_UP = "blurUp"
private const val STEP_VIGNETTE = "vignette"

// Boxes the frame down with SpatialBlur.BOX_DOWN_GLSL, each texel read as signal premultiplied by alpha.
private fun downFragment(signalGlsl: String): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uTargetSize;
  uniform float uBox;
  $signalGlsl

  vec4 premultipliedAt(ivec2 texel, ivec2 size) {
    vec4 read = texelFetch($FRAME, clamp(texel, ivec2(0), size - 1), 0);
    return vec4(toSignal(read.rgb) * read.a, read.a);
  }

${SpatialBlur.BOX_DOWN_GLSL.prependIndent("  ")}

  void main() {
    fragColor = boxedDown(textureSize($FRAME, 0), vUv, uTargetSize, uBox);
  }
  """.trimIndent()

private val BLUR_FRAGMENT =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uGap;
  uniform float uTaps;
  uniform float uWeights[${BlurPlan.MAX_TAPS + 1}];

  void main() {
    vec4 sum = uWeights[0] * textureLod($FRAME, vUv, 0.0);
    int taps = int(uTaps + 0.5);
    for (int i = 1; i <= taps; i++) {
      vec2 offset = float(i) * uGap;
      sum += uWeights[i] * (textureLod($FRAME, vUv + offset, 0.0) + textureLod($FRAME, vUv - offset, 0.0));
    }
    fragColor = sum;
  }
  """.trimIndent()

// Scales the blurred frame back up, divides the signal by the blurred alpha so the picture keeps its level beside what
// has none, and takes it back to the light the frame held with the alpha the frame came with. That alpha is fetched
// from the texel under the pixel, unfiltered, so a pixel with none keeps exactly none.
private fun upFragment(signalGlsl: String): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform sampler2D $ORIGINAL;
  $signalGlsl

  void main() {
    vec4 blurred = textureLod($FRAME, vUv, 0.0);
    vec3 signal = blurred.a > 0.0 ? blurred.rgb / blurred.a : vec3(0.0);
    ivec2 size = textureSize($ORIGINAL, 0);
    ivec2 texel = clamp(ivec2(floor(vUv * vec2(size))), ivec2(0), size - 1);
    float alpha = texelFetch($ORIGINAL, texel, 0).a;
    fragColor = vec4(fromSignal(signal), alpha);
  }
  """.trimIndent()

// vignetteGain, multiplied into the signal of the straight color.
private fun vignetteFragment(signalGlsl: String): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform float uAspect, uStrength, uRadius, uSoftness;
  $signalGlsl

${VIGNETTE_GAIN_GLSL.prependIndent("  ")}

  void main() {
    vec4 texel = textureLod($FRAME, vUv, 0.0);
    float gain = vignetteGain(vUv, uAspect, uStrength, uRadius, uSoftness);
    fragColor = vec4(fromSignal(toSignal(texel.rgb) * gain), texel.a);
  }
  """.trimIndent()
