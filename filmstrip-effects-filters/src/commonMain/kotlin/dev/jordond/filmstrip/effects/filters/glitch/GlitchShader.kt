package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.HASH_GLSL
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.STRAIGHT_READ_GLSL
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import dev.jordond.filmstrip.effects.filters.internal.uniformFloat

/**
 * The one GLSL ES 3.00 fragment source that draws a [Glitch], read by media3 and the browser.
 *
 * Every fixed number is written in from [GlitchSignal]'s constants, and everything an intensity or a frame changes is
 * a uniform [uniforms] hands back, so the shader holds no figure of its own. It reads the frame from [TEXTURE] at the
 * `vUv` varying, and takes [FLIP_V] as 1 when `v` runs up the picture. A read between texels mixes them
 * premultiplied, so a picture beside a clear bar keeps its colour.
 */
@InternalFilmstripApi
public object GlitchShader {
  /**
   * The sampler the frame is read from, which has to clamp to its edge.
   */
  public const val TEXTURE: String = "uTexture"

  /**
   * The uniform that says `v` runs up the picture, 1 when it does and 0 when it runs down.
   */
  public const val FLIP_V: String = "uFlipV"

  /**
   * The uniform that holds the frame size in pixels.
   */
  public const val RESOLUTION: String = "uResolution"

  /**
   * The fragment shader source.
   */
  public val FRAGMENT: String by lazy { fragmentSource() }

  /**
   * The float uniforms that draw [glitch] on a frame at [seconds] of composition time, by name.
   *
   * [RESOLUTION], [FLIP_V] and [TEXTURE] belong to whoever runs the pass. Seed and tick travel as floats, since each
   * fits the 24 bits a float holds exactly once masked. The burst and the split are read off [GlitchSignal] here, once
   * per frame.
   */
  public fun uniforms(
    glitch: Glitch,
    seconds: Double,
  ): Map<String, Float> {
    val params = GlitchParams.of(glitch)
    val tick = GlitchSignal.tickAt(seconds)
    return mapOf(
      "uSeed" to (glitch.seed and HASH_MASK).toFloat(),
      "uTick" to (tick and HASH_MASK).toFloat(),
      "uStrength" to uniformFloat(GlitchSignal.burstAt(glitch.seed, tick, params)?.strength ?: 0.0),
      "uSplit" to uniformFloat(GlitchSignal.channelSplit(glitch.seed, tick, params)),
      "uBandShift" to uniformFloat(params.bandShift),
      "uTearShift" to uniformFloat(params.tearShift),
    )
  }
}

/**
 * The row shift as one source GLSL ES 3.00 and Metal both compile, over a `glitchDraw(seed, pass, tick, cell)` each
 * language defines on its own hash. It walks the slices from the top to find the row's, the way
 * [GlitchSignal.rowShift] does, and adds any tear.
 */
internal val GLITCH_ROW_SHIFT: String =
  """
  uint glitchHash(uint seed, uint pass, uint tick, uint cell) {
    return uint(glitchDraw(seed, pass, tick, cell) * ${glslFloat(HASH_RANGE)});
  }

  float glitchSigned(uint seed, uint pass, uint tick, uint cell) {
    return glitchDraw(seed, pass, tick, cell) * 2.0 - 1.0;
  }

  float glitchRowShift(uint seed, uint tick, uint row, float strength, float bandShift, float tearShift) {
    if (strength <= 0.0) return 0.0;
    uint top = 0u;
    uint slice = 0u;
    for (int i = 0; i < ${GlitchSignal.maxSlices}; i++) {
      uint height = glitchHash(seed, ${PASS_SLICE_HEIGHT}u, tick, slice);
      uint rows = ${SLICE_MIN_ROWS}u + ((height * ${SLICE_SPREAD}u) >> ${HASH_BITS}u);
      if (row < top + rows) break;
      top += rows;
      slice += 1u;
    }
    float shift = 0.0;
    if (glitchHash(seed, ${PASS_SLICE_ROLL}u, tick, slice) < ${SLICE_SHIFT_BELOW}u) {
      shift = bandShift * strength * glitchSigned(seed, ${PASS_SLICE_SHIFT}u, tick, slice);
    }
    uint cell = row / ${TEAR_ROWS}u;
    if (glitchHash(seed, ${PASS_TEAR_ROLL}u, tick, cell) < ${TEAR_BELOW}u) {
      shift += tearShift * strength * glitchSigned(seed, ${PASS_TEAR_SHIFT}u, tick, cell);
    }
    return shift;
  }
  """

private fun fragmentSource(): String =
  """
  #version 300 es
  precision highp float;
  precision highp int;
  precision highp sampler2D;

  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D ${GlitchShader.TEXTURE};
  uniform vec2 ${GlitchShader.RESOLUTION};
  uniform float ${GlitchShader.FLIP_V};
  uniform float uSeed, uTick;
  uniform float uStrength, uSplit, uBandShift, uTearShift;

  const float ROWS = ${glslFloat(GLITCH_ROWS.toDouble())};

$HASH_GLSL
$STRAIGHT_READ_GLSL

  // draw reads its seed off uSeed, which is the seed every caller passes.
  float glitchDraw(uint seed, uint pass, uint tick, uint cell) { return draw(pass, tick, cell); }

$GLITCH_ROW_SHIFT

  vec4 readAt(float x) {
    return straightRead(${GlitchShader.TEXTURE}, vec2(clamp(x, 0.0, 1.0), vUv.y));
  }

  void main() {
    float height = ${GlitchShader.RESOLUTION}.y;
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${GlitchShader.FLIP_V});
    float pixelRow = clamp(floor(fromTop * height), 0.0, height - 1.0);
    // The half holds a row on an exact multiple above a float's rounding, as virtualRow's integer divide reads it.
    uint row = uint(floor((pixelRow * ROWS + 0.5) / height));
    uint tick = uint(uTick + 0.5);
    float shifted = vUv.x - glitchRowShift(uint(uSeed + 0.5), tick, row, uStrength, uBandShift, uTearShift);

    // Alpha moves with green, so a clear bar slides with the picture and lets the fill through.
    vec4 green = readAt(shifted);
    fragColor = vec4(readAt(shifted - uSplit).r, green.g, readAt(shifted + uSplit).b, green.a);
  }
  """.trimIndent()
