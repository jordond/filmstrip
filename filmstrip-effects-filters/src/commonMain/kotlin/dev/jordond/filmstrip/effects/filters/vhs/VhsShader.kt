package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The one GLSL ES 3.00 fragment source that draws a [Vhs], read by media3 and the browser.
 *
 * Every fixed number is written in from [VhsSignal]'s constants, and everything an intensity or a frame changes is a
 * uniform [uniforms] hands back, so the shader holds no figure of its own. It reads the frame from [TEXTURE] at the
 * `vUv` varying, and takes [FLIP_V] as 1 when `v` runs up the picture.
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
   * floats, since each fits the 24 bits a float holds exactly once masked.
   */
  public fun uniforms(
    vhs: Vhs,
    seconds: Double,
  ): Map<String, Float> {
    val params = VhsParams.of(vhs)
    return mapOf(
      "uSeed" to (vhs.seed and HASH_MASK).toFloat(),
      "uTick" to (VhsSignal.tickAt(seconds) and HASH_MASK).toFloat(),
      "uLumaSigma" to params.lumaSigma.toFloat(),
      "uChromaSigma" to params.chromaSigma.toFloat(),
      "uWideSigma" to params.wideSigma.toFloat(),
      "uHaloGain" to params.haloGain.toFloat(),
      "uJitter" to params.jitter.toFloat(),
      "uWave" to params.wave.toFloat(),
      "uHeadShift" to params.headShift.toFloat(),
      "uChromaShift" to params.chromaShift.toFloat(),
      "uLumaNoise" to params.lumaNoise.toFloat(),
      "uChromaNoise" to params.chromaNoise.toFloat(),
      "uDropoutChance" to params.dropoutChance.toFloat(),
      "uTrackChance" to params.trackingChance.toFloat(),
      "uTrackShift" to params.trackingShift.toFloat(),
      "uTrackNoise" to params.trackingNoise.toFloat(),
      "uDesaturation" to params.desaturation.toFloat(),
      "uLift" to params.lift.toFloat(),
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
  uniform float uTrackChance, uTrackShift, uTrackNoise;
  uniform float uDesaturation, uLift;

  const float KR = ${float(KR)};
  const float KB = ${float(KB)};
  const float KG = 1.0 - KR - KB;
  const float TICK_RATE = ${float(TICK_RATE)};
  const float ROWS = ${float(VIRTUAL_ROWS.toDouble())};
  const float PI = 3.14159265358979;
  const float TAU = 2.0 * PI;
  const float WINDOW_TICKS = ${float(trackWindowTicksValue())};
  const float HEAD_ROWS = ${float(headRowsValue())};
  const float LUMA_STEP = ${float(LUMA_TAP_STEP)};
  const float CHROMA_STEP = ${float(CHROMA_TAP_STEP)};
  const float LUMA_W[${LUMA_TAPS + 1}] = float[${LUMA_TAPS + 1}](${tapTable(LUMA_TAPS, LUMA_TAP_STEP)});
  const float CHROMA_W[${CHROMA_TAPS + 1}] = float[${CHROMA_TAPS + 1}](${tapTable(CHROMA_TAPS, CHROMA_TAP_STEP)});

  uint absorb(uint h, uint k) {
    k = (k * ${HASH_M1}u) & ${HASH_MASK}u;
    k ^= k >> ${HASH_ABSORB_SHIFT}u;
    k = (k * ${HASH_M2}u) & ${HASH_MASK}u;
    h = ((h ^ k) * ${HASH_FIVE}u + ${HASH_ADD}u) & ${HASH_MASK}u;
    return h ^ (h >> ${HASH_MIX_SHIFT}u);
  }

  float draw(uint pass, uint tick, uint cell) {
    uint h = absorb(absorb(absorb(uint(uSeed + 0.5), pass), tick & ${HASH_MASK}u), cell & ${HASH_MASK}u);
    h ^= h >> ${HASH_END_SHIFT}u;
    h = (h * ${HASH_M3}u) & ${HASH_MASK}u;
    h ^= h >> ${HASH_ABSORB_SHIFT}u;
    h = (h * ${HASH_M4}u) & ${HASH_MASK}u;
    h ^= h >> ${HASH_END_SHIFT}u;
    return float(h) / ${float(HASH_RANGE)};
  }

  float signedDraw(uint pass, uint tick, uint cell) { return draw(pass, tick, cell) * 2.0 - 1.0; }

  float tracking(uint tick, uint row) {
    uint windowTicks = uint(WINDOW_TICKS);
    uint window = tick / windowTicks;
    if (draw(${PASS_TRACK_EVENT}u, window, 0u) >= uTrackChance) return 0.0;
    float phase = float(tick % windowTicks) / WINDOW_TICKS;
    float start = draw(${PASS_TRACK_EVENT}u, window, 1u);
    float centre = fract(start + ${float(TRACK_DRIFT * TRACK_WINDOW_SECONDS)} * phase);
    float d = abs((float(row) + 0.5) / ROWS - centre);
    d = min(d, 1.0 - d);
    return sin(PI * phase) * (1.0 - smoothstep(0.0, ${float(0.5 * TRACK_BAND)}, d));
  }

  float rowShift(uint tick, uint row) {
    float seconds = float(tick) / TICK_RATE;
    float y = (float(row) + 0.5) / ROWS;
    float phase = draw(${PASS_WAVE}u, 0u, 0u);
    float first = sin(TAU * (${float(WAVE_CYCLES)} * y + ${float(WAVE_HZ)} * seconds + phase));
    float second = sin(TAU * (${float(WAVE_SECOND_CYCLES * WAVE_CYCLES)} * y +
      ${float(WAVE_SECOND_HZ * WAVE_HZ)} * seconds + 2.0 * phase));
    float shift = uJitter * signedDraw(${PASS_JITTER}u, tick, row);
    shift += uWave * (${float(WAVE_FIRST)} * first + ${float(1.0 - WAVE_FIRST)} * second);
    float intoBand = float(row) - (ROWS - HEAD_ROWS);
    if (intoBand >= 0.0) {
      float wobble = 1.0 + ${float(HEAD_WOBBLE)} * signedDraw(${PASS_HEAD}u, tick, 0u);
      shift += uHeadShift * wobble * pow((intoBand + ${float(HEAD_OFFSET)}) / HEAD_ROWS, ${float(HEAD_POWER)});
    }
    return shift + uTrackShift * tracking(tick, row) * signedDraw(${PASS_TRACK_SHIFT}u, tick, row);
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
    float len = ${float(DROPOUT_LENGTH)} *
      (${float(DROPOUT_LENGTH_MIN)} + ${float(DROPOUT_LENGTH_SPREAD)} * draw(${PASS_DROP_LEN}u, tick, row));
    float d = x - start;
    if (d < 0.0 || d > len) return 0.0;
    return smoothstep(0.0, ${float(DROPOUT_RISE)} * len, d) *
      (1.0 - smoothstep(${float(DROPOUT_FALL)} * len, len, d));
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
    return rgbToYcc(textureLod(${VhsShader.TEXTURE}, vec2(clamp(x, 0.0, 1.0), v), 0.0).rgb) * inside;
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

    float band = tracking(tick, row);
    float drop = dropout(tick, row, x);
    uint chromaRow = row / ${CHROMA_NOISE_ROWS}u;
    float chromaCell = ${float(STREAK * CHROMA_COARSENESS)};
    float chromaGain = (1.0 - ${float(TRACK_CHROMA_LOSS)} * band) * (1.0 - drop) * (1.0 - uDesaturation);

    float luma = sharp + uHaloGain * (sharp - wide);
    luma += uLumaNoise * streak(${PASS_LUMA_NOISE}u, tick, row, x, ${float(STREAK)});
    luma += uTrackNoise * band * streak(${PASS_TRACK_NOISE}u, tick, row, x, ${float(TRACK_NOISE_CELL)});
    luma += (1.0 - luma) * drop;
    luma = uLift + luma * (1.0 - uLift);
    float cb = (chroma.x + uChromaNoise * streak(${PASS_CB_NOISE}u, tick, chromaRow, x, chromaCell)) * chromaGain;
    float cr = (chroma.y + uChromaNoise * streak(${PASS_CR_NOISE}u, tick, chromaRow, x, chromaCell)) * chromaGain;

    // Alpha moves with the row, and colour and noise never raise it, so a clear bar lets the fill through.
    float alpha = textureLod(${VhsShader.TEXTURE}, vec2(clamp(shifted, 0.0, 1.0), vUv.y), 0.0).a;
    fragColor = vec4(clamp(yccToRgb(vec3(luma, cb, cr)), 0.0, 1.0), alpha);
  }
  """.trimIndent()

private fun tapTable(
  taps: Int,
  step: Double,
): String = (0..taps).joinToString { float(VhsSignal.tapWeight(it, step)) }

private fun trackWindowTicksValue(): Double = VhsSignal.trackWindowTicks.toDouble()

private fun headRowsValue(): Double = VhsSignal.headRows.toDouble()

// A double as a GLSL float literal. A browser prints a whole double with no point, which GLSL reads as an int.
private fun float(value: Double): String {
  val text = value.toString()
  return if (text.contains('.') || text.contains('e') || text.contains('E')) text else "$text.0"
}
