package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.HASH_GLSL
import dev.jordond.filmstrip.effects.filters.internal.HASH_MASK
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.STRAIGHT_READ_GLSL
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import dev.jordond.filmstrip.effects.filters.internal.uniformFloat

/**
 * The one GLSL ES 3.00 fragment source that draws a [Vhs], read by media3 and the browser.
 *
 * Every fixed number is written in from [VhsSignal]'s constants, and everything an intensity or a frame changes is a
 * uniform [uniforms] hands back, so the shader holds no figure of its own. It reads the frame from [TEXTURE] at the
 * `vUv` varying, and takes [FLIP_V] as 1 when `v` runs up the picture. A read between texels mixes them
 * premultiplied, so a picture beside a clear bar keeps its colour.
 */
@InternalFilmstripApi
public object VhsShader {
  /**
   * The sampler the frame is read from.
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
   * The float uniforms that draw [vhs] on a frame at [seconds] of composition time, by name.
   *
   * [RESOLUTION], [FLIP_V] and [TEXTURE] belong to whoever runs the pass. Seed and tick travel as
   * floats, since each fits the 24 bits a float holds exactly once masked. The tracking band's place on the frame is
   * read off [VhsSignal.trackingBand] here, once per frame.
   */
  public fun uniforms(
    vhs: Vhs,
    seconds: Double,
  ): Map<String, Float> {
    val params = VhsParams.of(vhs)
    val tick = VhsSignal.tickAt(seconds)
    val band = VhsSignal.trackingBand(vhs.seed, tick, params)
    return mapOf(
      "uSeed" to (vhs.seed and HASH_MASK).toFloat(),
      "uTick" to (tick and HASH_MASK).toFloat(),
      "uLumaSigma" to uniformFloat(params.lumaSigma),
      "uChromaSigma" to uniformFloat(params.chromaSigma),
      "uWideSigma" to uniformFloat(params.wideSigma),
      "uHaloGain" to uniformFloat(params.haloGain),
      "uJitter" to uniformFloat(params.jitter),
      "uWave" to uniformFloat(params.wave),
      "uHeadShift" to uniformFloat(params.headShift),
      "uChromaShift" to uniformFloat(params.chromaShift),
      "uLumaNoise" to uniformFloat(params.lumaNoise),
      "uChromaNoise" to uniformFloat(params.chromaNoise),
      "uDropoutChance" to uniformFloat(params.dropoutChance),
      "uTrackCentre" to uniformFloat(band.centre),
      "uTrackStrength" to uniformFloat(band.strength),
      "uTrackHalfHeight" to uniformFloat(params.trackingHalfHeight),
      "uTrackShift" to uniformFloat(params.trackingShift),
      "uTrackNoise" to uniformFloat(params.trackingNoise),
      "uTrackSnow" to uniformFloat(params.trackingSnow),
      "uDesaturation" to uniformFloat(params.desaturation),
      "uLift" to uniformFloat(params.lift),
    )
  }
}

private fun fragmentSource(): String =
  """
  #version 300 es
  precision highp float;
  precision highp int;
  precision highp sampler2D;

  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D ${VhsShader.TEXTURE};
  uniform vec2 ${VhsShader.RESOLUTION};
  uniform float ${VhsShader.FLIP_V};
  uniform float uSeed, uTick;
  uniform float uLumaSigma, uChromaSigma, uWideSigma, uHaloGain;
  uniform float uJitter, uWave, uHeadShift, uChromaShift;
  uniform float uLumaNoise, uChromaNoise, uDropoutChance;
  uniform float uTrackCentre, uTrackStrength, uTrackHalfHeight, uTrackShift, uTrackNoise, uTrackSnow;
  uniform float uDesaturation, uLift;

  const float KR = ${glslFloat(KR)};
  const float KB = ${glslFloat(KB)};
  const float KG = 1.0 - KR - KB;
  const float TICK_RATE = ${glslFloat(TICK_RATE)};
  const float ROWS = ${glslFloat(VIRTUAL_ROWS.toDouble())};
  const float PI = 3.14159265358979;
  const float TAU = 2.0 * PI;
  const float HEAD_ROWS = ${glslFloat(headRowsValue())};
  const float LUMA_STEP = ${glslFloat(LUMA_TAP_STEP)};
  const float CHROMA_STEP = ${glslFloat(CHROMA_TAP_STEP)};
  const float LUMA_W[${LUMA_TAPS + 1}] = float[${LUMA_TAPS + 1}](${tapTable(LUMA_TAPS, LUMA_TAP_STEP)});
  const float CHROMA_W[${CHROMA_TAPS + 1}] = float[${CHROMA_TAPS + 1}](${tapTable(CHROMA_TAPS, CHROMA_TAP_STEP)});

$HASH_GLSL
$STRAIGHT_READ_GLSL

  float signedDraw(uint pass, uint tick, uint cell) { return draw(pass, tick, cell) * 2.0 - 1.0; }

  float tracking(uint row) {
    if (uTrackStrength == 0.0) return 0.0;
    float d = abs((float(row) + 0.5) / ROWS - uTrackCentre);
    d = min(d, 1.0 - d);
    return uTrackStrength * (1.0 - smoothstep(0.0, uTrackHalfHeight, d));
  }

  float rowShift(uint tick, uint row) {
    float seconds = float(tick) / TICK_RATE;
    float y = (float(row) + 0.5) / ROWS;
    float phase = draw(${PASS_WAVE}u, 0u, 0u);
    float first = sin(TAU * (${glslFloat(WAVE_CYCLES)} * y + ${glslFloat(WAVE_HZ)} * seconds + phase));
    float second = sin(TAU * (${glslFloat(WAVE_SECOND_CYCLES * WAVE_CYCLES)} * y +
      ${glslFloat(WAVE_SECOND_HZ * WAVE_HZ)} * seconds + 2.0 * phase));
    float shift = uJitter * signedDraw(${PASS_JITTER}u, tick, row);
    shift += uWave * (${glslFloat(WAVE_FIRST)} * first + ${glslFloat(1.0 - WAVE_FIRST)} * second);
    float intoBand = float(row) - (ROWS - HEAD_ROWS);
    if (intoBand >= 0.0) {
      float wobble = 1.0 + ${glslFloat(HEAD_WOBBLE)} * signedDraw(${PASS_HEAD}u, tick, 0u);
      shift += uHeadShift * wobble * pow((intoBand + ${glslFloat(HEAD_OFFSET)}) / HEAD_ROWS, ${glslFloat(HEAD_POWER)});
    }
    return shift + uTrackShift * tracking(row) * signedDraw(${PASS_TRACK_SHIFT}u, tick, row);
  }

  float streak(uint pass, uint tick, uint row, float x, float cellLength) {
    float u = x / cellLength + draw(pass, tick, row);
    float f = fract(u);
    uint cell = uint(floor(u));
    float a = signedDraw(pass, tick, row + (cell + 1u) * ${ROW_CELL_STRIDE}u);
    float b = signedDraw(pass, tick, row + (cell + 2u) * ${ROW_CELL_STRIDE}u);
    return a + (b - a) * (f * f * (3.0 - 2.0 * f));
  }

  float dropout(uint tick, uint row, float x) {
    if (draw(${PASS_DROP_EVENT}u, tick, row) >= uDropoutChance) return 0.0;
    float start = draw(${PASS_DROP_POS}u, tick, row);
    float len = ${glslFloat(DROPOUT_LENGTH)} *
      (${glslFloat(DROPOUT_LENGTH_MIN)} + ${glslFloat(DROPOUT_LENGTH_SPREAD)} * draw(${PASS_DROP_LEN}u, tick, row));
    float d = x - start;
    if (d < 0.0 || d > len) return 0.0;
    return smoothstep(0.0, ${glslFloat(DROPOUT_RISE)} * len, d) *
      (1.0 - smoothstep(${glslFloat(DROPOUT_FALL)} * len, len, d));
  }

  vec3 rgbToYcc(vec3 c) {
    float y = KR * c.r + KG * c.g + KB * c.b;
    return vec3(y, (c.b - y) / (2.0 * (1.0 - KB)), (c.r - y) / (2.0 * (1.0 - KR)));
  }

  vec3 yccToRgb(vec3 c) {
    float r = c.x + 2.0 * (1.0 - KR) * c.z;
    float b = c.x + 2.0 * (1.0 - KB) * c.y;
    return vec3(r, (c.x - KR * r - KB * b) / KG, b);
  }

  // The frame in Y'CbCr at x, black past either edge the way an exposed line of tape is.
  vec3 tape(float x, float v) {
    float inside = clamp(x * ${VhsShader.RESOLUTION}.x + 0.5, 0.0, 1.0) *
      clamp((1.0 - x) * ${VhsShader.RESOLUTION}.x + 0.5, 0.0, 1.0);
    return rgbToYcc(straightRead(${VhsShader.TEXTURE}, vec2(clamp(x, 0.0, 1.0), v)).rgb) * inside;
  }

  float lumaBlur(float x, float v, float sigma) {
    float sum = LUMA_W[0] * tape(x, v).x;
    float total = LUMA_W[0];
    float gap = LUMA_STEP * sigma;
    for (int i = 1; i <= $LUMA_TAPS; i++) {
      float offset = float(i) * gap;
      sum += LUMA_W[i] * (tape(x + offset, v).x + tape(x - offset, v).x);
      total += 2.0 * LUMA_W[i];
    }
    return sum / total;
  }

  vec2 chromaBlur(float x, float v, float sigma) {
    vec2 sum = CHROMA_W[0] * tape(x, v).yz;
    float total = CHROMA_W[0];
    float gap = CHROMA_STEP * sigma;
    for (int i = 1; i <= $CHROMA_TAPS; i++) {
      float offset = float(i) * gap;
      sum += CHROMA_W[i] * (tape(x + offset, v).yz + tape(x - offset, v).yz);
      total += 2.0 * CHROMA_W[i];
    }
    return sum / total;
  }

  void main() {
    float height = ${VhsShader.RESOLUTION}.y;
    float fromTop = mix(vUv.y, 1.0 - vUv.y, ${VhsShader.FLIP_V});
    float pixelRow = clamp(floor(fromTop * height), 0.0, height - 1.0);
    // The half holds a row on an exact multiple above a float's rounding, as virtualRow's integer divide reads it.
    uint row = uint(floor((pixelRow * ROWS + 0.5) / height));
    uint tick = uint(uTick + 0.5);
    float x = clamp(vUv.x, 0.0, 1.0);
    float shifted = vUv.x - rowShift(tick, row);

    float sharp = lumaBlur(shifted, vUv.y, uLumaSigma);
    float wide = lumaBlur(shifted, vUv.y, uWideSigma);
    vec2 chroma = chromaBlur(shifted - uChromaShift, vUv.y, uChromaSigma);

    float band = tracking(row);
    float drop = dropout(tick, row, x);
    float snowWeight = uTrackSnow * band;
    float snow = 0.0;
    if (snowWeight > 0.0) {
      snow = clamp(0.5 + ${glslFloat(SNOW_CONTRAST * 0.5)} *
        streak(${PASS_SNOW}u, tick, row, x, ${glslFloat(SNOW_CELL)}), 0.0, 1.0);
    }
    uint chromaRow = row / ${CHROMA_NOISE_ROWS}u;
    float chromaCell = ${glslFloat(STREAK * CHROMA_COARSENESS)};
    float chromaGain = (1.0 - ${glslFloat(TRACK_CHROMA_LOSS)} * band) * (1.0 - drop) * (1.0 - uDesaturation) *
      (1.0 - snowWeight);

    float luma = sharp + uHaloGain * (sharp - wide);
    luma += uLumaNoise * streak(${PASS_LUMA_NOISE}u, tick, row, x, ${glslFloat(STREAK)});
    luma += uTrackNoise * band * streak(${PASS_TRACK_NOISE}u, tick, row, x, ${glslFloat(TRACK_NOISE_CELL)});
    luma += (1.0 - luma) * drop;
    luma += (snow - luma) * snowWeight;
    luma = uLift + luma * (1.0 - uLift);
    float cb = (chroma.x + uChromaNoise * streak(${PASS_CB_NOISE}u, tick, chromaRow, x, chromaCell)) * chromaGain;
    float cr = (chroma.y + uChromaNoise * streak(${PASS_CR_NOISE}u, tick, chromaRow, x, chromaCell)) * chromaGain;

    // Alpha moves with the row, and colour and noise never raise it, so a clear bar lets the fill through.
    float alpha = straightRead(${VhsShader.TEXTURE}, vec2(clamp(shifted, 0.0, 1.0), vUv.y)).a;
    fragColor = vec4(clamp(yccToRgb(vec3(luma, cb, cr)), 0.0, 1.0), alpha);
  }
  """.trimIndent()

private fun tapTable(
  taps: Int,
  step: Double,
): String = (0..taps).joinToString { glslFloat(VhsSignal.tapWeight(it, step)) }

private fun headRowsValue(): Double = VhsSignal.headRows.toDouble()
