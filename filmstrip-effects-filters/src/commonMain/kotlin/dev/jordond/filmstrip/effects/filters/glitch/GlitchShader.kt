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
   * per frame, and the hash a row of blocks breaks under off [GlitchBlocks.breakBelow], a whole number a float holds
   * exactly.
   */
  public fun uniforms(
    glitch: Glitch,
    seconds: Double,
  ): Map<String, Float> {
    val params = GlitchParams.of(glitch)
    val tick = GlitchSignal.tickAt(seconds)
    val strength = GlitchSignal.burstAt(glitch.seed, tick, params)?.strength ?: 0.0
    return mapOf(
      "uSeed" to (glitch.seed and HASH_MASK).toFloat(),
      "uTick" to (tick and HASH_MASK).toFloat(),
      "uStrength" to uniformFloat(strength),
      "uSplit" to uniformFloat(GlitchSignal.channelSplit(glitch.seed, tick, params)),
      "uBandShift" to uniformFloat(params.bandShift),
      "uTearShift" to uniformFloat(params.tearShift),
      "uBlockBelow" to GlitchBlocks.breakBelow(strength, params).toFloat(),
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

/**
 * [GlitchBlocks.sourceOf] as one source GLSL ES 3.00 and Metal both compile, over [GLITCH_ROW_SHIFT]'s `glitchHash`.
 * Each language defines `GLITCH_INT2` as its own two int vector first. The row breaks under `below`, which is
 * [GlitchBlocks.breakBelow] of the frame's burst.
 */
internal val GLITCH_BLOCK_SOURCE: String =
  """
  int glitchBlockSide(int height) {
    return max(1, (height * ${2 * BLOCK_ROWS} + $GLITCH_ROWS) / ${2 * GLITCH_ROWS});
  }

  uint glitchSteps(uint hash, uint count) {
    return ((hash >> ${STEP_DROP_BITS}u) * count) >> ${STEP_KEEP_BITS}u;
  }

  GLITCH_INT2 glitchSource(uint seed, uint tick, int x, int y, int width, int height, float below) {
    GLITCH_INT2 own = GLITCH_INT2(x, y);
    if (below <= 0.0) return own;
    int side = glitchBlockSide(height);
    int blockRow = y / side;
    uint row = uint(blockRow);
    if (glitchHash(seed, ${PASS_BLOCK_ROW}u, tick, row) >= uint(below + 0.5)) return own;
    uint columns = uint((width + side - 1) / side);
    int start = int(glitchSteps(glitchHash(seed, ${PASS_BLOCK_RUN}u, tick, 2u * row), columns));
    int length = 1 + int(glitchSteps(glitchHash(seed, ${PASS_BLOCK_RUN}u, tick, 2u * row + 1u), ${RUN_MAX_BLOCKS}u));
    int index = x / side - start;
    if (index < 0 || index >= length) return own;
    uint cell = row * ${RUN_MAX_BLOCKS}u + uint(index);
    if (glitchHash(seed, ${PASS_BLOCK_KIND}u, tick, cell) < ${SMEAR_BELOW}u) {
      return GLITCH_INT2(x, max(0, blockRow * side - 1));
    }
    int draw = int(glitchSteps(glitchHash(seed, ${PASS_BLOCK_MOVE}u, tick, cell), ${BLOCK_MOVES}u));
    int move = draw >= ${BLOCK_MOVES / 2} ? draw + 1 : draw;
    return GLITCH_INT2(
      clamp(x + (move % $BLOCK_SPAN - $BLOCK_REACH) * side, 0, width - 1),
      clamp(y + (move / $BLOCK_SPAN - $BLOCK_REACH) * side, 0, height - 1)
    );
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
  uniform float uStrength, uSplit, uBandShift, uTearShift, uBlockBelow;

  const float ROWS = ${glslFloat(GLITCH_ROWS.toDouble())};

$HASH_GLSL
$STRAIGHT_READ_GLSL

  // draw reads its seed off uSeed, which is the seed every caller passes.
  float glitchDraw(uint seed, uint pass, uint tick, uint cell) { return draw(pass, tick, cell); }

$GLITCH_ROW_SHIFT

  #define GLITCH_INT2 ivec2
$GLITCH_BLOCK_SOURCE

  // A texel of the broken frame, premultiplied, at a column and a row counted from the top.
  vec4 blockedTexel(int column, int fromTop, uint seed, uint tick) {
    ivec2 size = textureSize(${GlitchShader.TEXTURE}, 0);
    ivec2 source = glitchSource(seed, tick, clamp(column, 0, size.x - 1), fromTop, size.x, size.y, uBlockBelow);
    int row = ${GlitchShader.FLIP_V} > 0.5 ? size.y - 1 - source.y : source.y;
    vec4 read = texelFetch(${GlitchShader.TEXTURE}, ivec2(source.x, row), 0);
    return vec4(read.rgb * read.a, read.a);
  }

  // Reads the broken frame between the two columns either side of x on its own row, the way straightRead does.
  vec4 readAt(float x, int fromTop, uint seed, uint tick) {
    if (uBlockBelow <= 0.0) return straightRead(${GlitchShader.TEXTURE}, vec2(clamp(x, 0.0, 1.0), vUv.y));
    float at = clamp(x, 0.0, 1.0) * ${GlitchShader.RESOLUTION}.x - 0.5;
    float corner = floor(at);
    int low = int(corner);
    vec4 mixed = mix(blockedTexel(low, fromTop, seed, tick), blockedTexel(low + 1, fromTop, seed, tick), at - corner);
    return vec4(mixed.a > 0.0 ? mixed.rgb / mixed.a : vec3(0.0), mixed.a);
  }

  void main() {
    float height = ${GlitchShader.RESOLUTION}.y;
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${GlitchShader.FLIP_V});
    float pixelRow = clamp(floor(fromTop * height), 0.0, height - 1.0);
    // The half holds a row on an exact multiple above a float's rounding, as virtualRow's integer divide reads it.
    uint row = uint(floor((pixelRow * ROWS + 0.5) / height));
    uint tick = uint(uTick + 0.5);
    uint seed = uint(uSeed + 0.5);
    int fromTopRow = int(pixelRow);
    float shifted = vUv.x - glitchRowShift(seed, tick, row, uStrength, uBandShift, uTearShift);

    // Alpha moves with green, so a clear bar slides with the picture and lets the fill through.
    vec4 green = readAt(shifted, fromTopRow, seed, tick);
    fragColor = vec4(
      readAt(shifted - uSplit, fromTopRow, seed, tick).r,
      green.g,
      readAt(shifted + uSplit, fromTopRow, seed, tick).b,
      green.a
    );
  }
  """.trimIndent()
