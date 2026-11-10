package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.VIGNETTE_GAIN_GLSL
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CELLS_ACROSS
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CELL_SCALE
import dev.jordond.filmstrip.effects.filters.film.PASS_GRAIN_FIRST
import dev.jordond.filmstrip.effects.filters.film.PASS_GRAIN_SECOND
import dev.jordond.filmstrip.effects.filters.internal.HASH_GLSL
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.glslFloat

/**
 * The GLSL ES 3.00 fragment source that draws a [NightVision], read by media3 and the browser.
 *
 * Every fixed number is written in from [NightVisionSignal]'s constants and film's grain, and what a control, a frame
 * or the clock changes is a uniform from [uniforms]. It reads at the `vUv` varying, and takes [FLIP_V] as 1 when `v`
 * runs up the picture.
 *
 * The look is drawn on a pixel's straight codes and the pixel keeps its alpha, so a clear bar stays clear.
 */
@InternalFilmstripApi
public object NightVisionShader {
  /**
   * The sampler the frame is bound to.
   */
  public const val TEXTURE: String = "uTexture"

  /**
   * The uniform set to 1 when `v` runs up the picture, and 0 when it runs down.
   */
  public const val FLIP_V: String = "uFlipV"

  /**
   * The fragment source.
   */
  public val FRAGMENT: String by lazy { fragmentSource() }

  /**
   * The uniforms for [nightVision] on a frame [width] by [height] at [seconds] of composition time, by name, each a
   * float or a vec by its length.
   *
   * It holds every name [FRAGMENT] reads apart from [FLIP_V] and [TEXTURE], which belong to whoever runs the pass.
   */
  public fun uniforms(
    nightVision: NightVision,
    seconds: Double,
    width: Int,
    height: Int,
  ): Map<String, FloatArray> {
    val params = NightVisionParams.of(nightVision)
    return mapOf(
      RESOLUTION to floatArrayOf(width.toFloat(), height.toFloat()),
      SEED to floatArrayOf((params.seed and HASH_MASK).toFloat()),
      TICK to floatArrayOf((FilmSignal.grainTickAt(seconds) and HASH_MASK).toFloat()),
      CELL_SPAN to floatArrayOf(FilmSignal.grainCellSpan(width, height).toFloat()),
      INTENSITY to floatArrayOf(params.intensity.toFloat()),
    )
  }
}

private const val RESOLUTION = "uResolution"

// HASH_GLSL reads the seed under this name.
private const val SEED = "uSeed"
private const val TICK = "uTick"
private const val CELL_SPAN = "uCellSpan"
private const val INTENSITY = "uIntensity"

private fun glslVec3(colour: DoubleArray): String =
  "vec3(${glslFloat(colour[0])}, ${glslFloat(colour[1])}, ${glslFloat(colour[2])})"

private fun fragmentSource(): String {
  val params = NightVisionParams.at(1.0)
  val knee = glslFloat(NIGHT_VISION_KNEE)
  val room = glslFloat(1.0 - NIGHT_VISION_KNEE)
  val vignette =
    "${glslFloat(NIGHT_VISION_VIGNETTE_STRENGTH)}, ${glslFloat(NIGHT_VISION_VIGNETTE_RADIUS)}, " +
      glslFloat(NIGHT_VISION_VIGNETTE_SOFTNESS)
  return """
  #version 300 es
  precision highp float;
  precision highp int;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D ${NightVisionShader.TEXTURE};
  uniform float ${NightVisionShader.FLIP_V};
  uniform vec2 $RESOLUTION;
  uniform float $SEED, $TICK, $CELL_SPAN, $INTENSITY;
$HASH_GLSL

  float lumaOf(vec3 c) {
    return ${glslFloat(KR)} * c.r + ${glslFloat(1.0 - KR - KB)} * c.g + ${glslFloat(KB)} * c.b;
  }

  float lift(float luma) {
    float lifted = ${glslFloat(params.gain)} * clamp(luma, 0.0, 1.0);
    if (lifted <= $knee) return lifted;
    return $knee + $room * (1.0 - exp(-(lifted - $knee) / $room));
  }

  vec3 ramp(float level) {
    float at = clamp(level, 0.0, 1.0);
    if (at <= ${glslFloat(RAMP_MID)}) return ${glslVec3(RAMP_MID_COLOUR)} * (at / ${glslFloat(RAMP_MID)});
    float t = (at - ${glslFloat(RAMP_MID)}) / ${glslFloat(1.0 - RAMP_MID)};
    return mix(${glslVec3(RAMP_MID_COLOUR)}, ${glslVec3(RAMP_TOP_COLOUR)}, t);
  }

${VIGNETTE_GAIN_GLSL.prependIndent("  ")}

  void main() {
    vec4 texel = textureLod(${NightVisionShader.TEXTURE}, vUv, 0.0);
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${NightVisionShader.FLIP_V});
    vec2 pixel = vec2(
      clamp(floor(vUv.x * $RESOLUTION.x), 0.0, $RESOLUTION.x - 1.0),
      clamp(floor(fromTop * $RESOLUTION.y), 0.0, $RESOLUTION.y - 1.0)
    );
    uint span = uint($CELL_SPAN + 0.5);
    uint row = uint(pixel.y) * ${GRAIN_CELL_SCALE}u / span;
    uint cell = row * ${GRAIN_CELLS_ACROSS}u + uint(pixel.x) * ${GRAIN_CELL_SCALE}u / span;
    uint tick = uint($TICK + 0.5);
    float grain = draw(${PASS_GRAIN_FIRST}u, tick, cell) + draw(${PASS_GRAIN_SECOND}u, tick, cell) - 1.0;
    vec3 look = ramp(lift(lumaOf(texel.rgb) + ${glslFloat(params.noise)} * grain));
    float gain = vignetteGain((pixel + 0.5) / $RESOLUTION, $RESOLUTION.x / $RESOLUTION.y, $vignette);
    fragColor = vec4(mix(texel.rgb, look * gain, $INTENSITY), texel.a);
  }
    """.trimIndent()
}
