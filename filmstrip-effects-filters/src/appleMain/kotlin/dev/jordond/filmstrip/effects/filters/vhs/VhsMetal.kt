package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR

/**
 * The name of the Core Image kernel in [vhsMetal].
 */
internal const val VHS_KERNEL: String = "filmstripVhs"

/**
 * Metal source for the Core Image kernel [VHS_KERNEL], the same steps [VhsShader] takes.
 *
 * Every fixed number is written in from [VhsSignal]'s constants, and everything an intensity or a frame changes is a
 * kernel argument. The kernel reads the frame's own codes, premultiplied the way Core Image holds them, and returns
 * them premultiplied. Alpha is read at the shifted position, so it moves with the row, and colour never raises it.
 */
internal fun vhsMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  constant float VHS_KR = ${metal(KR)};
  constant float VHS_KB = ${metal(KB)};
  constant float VHS_KG = 1.0f - VHS_KR - VHS_KB;
  constant float VHS_TICK_RATE = ${metal(TICK_RATE)};
  constant float VHS_ROWS = ${metal(VIRTUAL_ROWS.toDouble())};
  constant float VHS_PI = 3.14159265358979f;
  constant float VHS_TAU = 2.0f * VHS_PI;
  constant float VHS_WINDOW_TICKS = ${metal(VhsSignal.trackWindowTicks.toDouble())};
  constant float VHS_HEAD_ROWS = ${metal(VhsSignal.headRows.toDouble())};
  constant float VHS_LUMA_W[${LUMA_TAPS + 1}] = {${tapTable(LUMA_TAPS, LUMA_TAP_STEP)}};
  constant float VHS_CHROMA_W[${CHROMA_TAPS + 1}] = {${tapTable(CHROMA_TAPS, CHROMA_TAP_STEP)}};

  struct VhsTape {
    uint seed;
    uint tick;
    float originX;
    float originY;
    float width;
    float height;
    float lumaSigma;
    float chromaSigma;
    float wideSigma;
    float haloGain;
    float jitter;
    float wave;
    float headShift;
    float chromaShift;
    float lumaNoise;
    float chromaNoise;
    float dropoutChance;
    float desaturation;
    float trackChance;
    float trackShift;
    float trackNoise;
    float lift;
  };

$HASH_MSL

  float vhsSigned(uint seed, uint pass, uint tick, uint cell) {
    return filterDraw(seed, pass, tick, cell) * 2.0f - 1.0f;
  }

  float vhsSmooth(float from, float to, float value) {
    float t = clamp((value - from) / (to - from), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
  }

  float vhsTracking(VhsTape tape, uint row) {
    uint windowTicks = uint(VHS_WINDOW_TICKS);
    uint window = tape.tick / windowTicks;
    if (filterDraw(tape.seed, ${PASS_TRACK_EVENT}u, window, 0u) >= tape.trackChance) return 0.0f;
    float phase = float(tape.tick % windowTicks) / VHS_WINDOW_TICKS;
    float start = filterDraw(tape.seed, ${PASS_TRACK_EVENT}u, window, 1u);
    float centre = fract(start + ${metal(TRACK_DRIFT * TRACK_WINDOW_SECONDS)} * phase);
    float d = abs((float(row) + 0.5f) / VHS_ROWS - centre);
    d = min(d, 1.0f - d);
    return sin(VHS_PI * phase) * (1.0f - vhsSmooth(0.0f, ${metal(0.5 * TRACK_BAND)}, d));
  }

  float vhsRowShift(VhsTape tape, uint row) {
    float seconds = float(tape.tick) / VHS_TICK_RATE;
    float y = (float(row) + 0.5f) / VHS_ROWS;
    float phase = filterDraw(tape.seed, ${PASS_WAVE}u, 0u, 0u);
    float first = sin(VHS_TAU * (${metal(WAVE_CYCLES)} * y + ${metal(WAVE_HZ)} * seconds + phase));
    float second = sin(VHS_TAU * (${metal(WAVE_SECOND_CYCLES * WAVE_CYCLES)} * y +
      ${metal(WAVE_SECOND_HZ * WAVE_HZ)} * seconds + 2.0f * phase));
    float shift = tape.jitter * vhsSigned(tape.seed, ${PASS_JITTER}u, tape.tick, row);
    shift += tape.wave * (${metal(WAVE_FIRST)} * first + ${metal(1.0 - WAVE_FIRST)} * second);
    float intoBand = float(row) - (VHS_ROWS - VHS_HEAD_ROWS);
    if (intoBand >= 0.0f) {
      float wobble = 1.0f + ${metal(HEAD_WOBBLE)} * vhsSigned(tape.seed, ${PASS_HEAD}u, tape.tick, 0u);
      shift += tape.headShift * wobble * pow((intoBand + ${metal(HEAD_OFFSET)}) / VHS_HEAD_ROWS, ${metal(HEAD_POWER)});
    }
    return shift + tape.trackShift * vhsTracking(tape, row) *
      vhsSigned(tape.seed, ${PASS_TRACK_SHIFT}u, tape.tick, row);
  }

  float vhsStreak(VhsTape tape, uint pass, uint row, float x, float cellLength) {
    float u = x / cellLength + filterDraw(tape.seed, pass, tape.tick, row);
    float f = fract(u);
    uint cell = uint(floor(u));
    float a = vhsSigned(tape.seed, pass, tape.tick, row + (cell + 1u) * ${ROW_CELL_STRIDE}u);
    float b = vhsSigned(tape.seed, pass, tape.tick, row + (cell + 2u) * ${ROW_CELL_STRIDE}u);
    return a + (b - a) * (f * f * (3.0f - 2.0f * f));
  }

  float vhsDropout(VhsTape tape, uint row, float x) {
    if (filterDraw(tape.seed, ${PASS_DROP_EVENT}u, tape.tick, row) >= tape.dropoutChance) return 0.0f;
    float start = filterDraw(tape.seed, ${PASS_DROP_POS}u, tape.tick, row);
    float len = ${metal(DROPOUT_LENGTH)} * (${metal(DROPOUT_LENGTH_MIN)} +
      ${metal(DROPOUT_LENGTH_SPREAD)} * filterDraw(tape.seed, ${PASS_DROP_LEN}u, tape.tick, row));
    float d = x - start;
    if (d < 0.0f || d > len) return 0.0f;
    return vhsSmooth(0.0f, ${metal(DROPOUT_RISE)} * len, d) * (1.0f - vhsSmooth(${metal(DROPOUT_FALL)} * len, len, d));
  }

  float3 vhsRgbToYcc(float3 c) {
    float y = VHS_KR * c.r + VHS_KG * c.g + VHS_KB * c.b;
    return float3(y, (c.b - y) / (2.0f * (1.0f - VHS_KB)), (c.r - y) / (2.0f * (1.0f - VHS_KR)));
  }

  float3 vhsYccToRgb(float3 c) {
    float r = c.x + 2.0f * (1.0f - VHS_KR) * c.z;
    float b = c.x + 2.0f * (1.0f - VHS_KB) * c.y;
    return float3(r, (c.x - VHS_KR * r - VHS_KB * b) / VHS_KG, b);
  }

  float4 vhsRead(coreimage::sampler frame, VhsTape tape, float x, float y) {
    return frame.sample(frame.transform(float2(tape.originX + clamp(x, 0.0f, 1.0f) * tape.width, y)));
  }

  // The frame in Y'CbCr at x, black past either edge the way an exposed line of tape is. An 8 bit source holds nothing
  // past 0 to 1, so a working space value that does is cut back to read the same.
  float3 vhsOnTape(coreimage::sampler frame, VhsTape tape, float x, float y) {
    float inside = clamp(x * tape.width + 0.5f, 0.0f, 1.0f) * clamp((1.0f - x) * tape.width + 0.5f, 0.0f, 1.0f);
    return vhsRgbToYcc(clamp(vhsRead(frame, tape, x, y).rgb, 0.0f, 1.0f)) * inside;
  }

  float vhsLumaBlur(coreimage::sampler frame, VhsTape tape, float x, float y, float sigma) {
    float sum = VHS_LUMA_W[0] * vhsOnTape(frame, tape, x, y).x;
    float total = VHS_LUMA_W[0];
    float gap = ${metal(LUMA_TAP_STEP)} * sigma;
    for (int i = 1; i <= $LUMA_TAPS; i++) {
      float offset = float(i) * gap;
      sum += VHS_LUMA_W[i] * (vhsOnTape(frame, tape, x + offset, y).x + vhsOnTape(frame, tape, x - offset, y).x);
      total += 2.0f * VHS_LUMA_W[i];
    }
    return sum / total;
  }

  float2 vhsChromaBlur(coreimage::sampler frame, VhsTape tape, float x, float y, float sigma) {
    float2 sum = VHS_CHROMA_W[0] * vhsOnTape(frame, tape, x, y).yz;
    float total = VHS_CHROMA_W[0];
    float gap = ${metal(CHROMA_TAP_STEP)} * sigma;
    for (int i = 1; i <= $CHROMA_TAPS; i++) {
      float offset = float(i) * gap;
      sum += VHS_CHROMA_W[i] * (vhsOnTape(frame, tape, x + offset, y).yz + vhsOnTape(frame, tape, x - offset, y).yz);
      total += 2.0f * VHS_CHROMA_W[i];
    }
    return sum / total;
  }

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $VHS_KERNEL(
      sampler frame,
      float4 rect,
      float2 clock,
      float4 blur,
      float4 shifts,
      float4 noise,
      float4 track,
      destination dest
    ) {
      VhsTape tape = {
        uint(clock.x + 0.5f), uint(clock.y + 0.5f),
        rect.x, rect.y, rect.z, rect.w,
        blur.x, blur.y, blur.z, blur.w,
        shifts.x, shifts.y, shifts.z, shifts.w,
        noise.x, noise.y, noise.z, noise.w,
        track.x, track.y, track.z, track.w
      };
      float2 at = dest.coord();
      float u = (at.x - tape.originX) / tape.width;
      float fromTop = tape.originY + tape.height - at.y;
      // Core Image runs a kernel past its extent when a later composite asks, and the frame is held at its edge there.
      if (u < 0.0f || u >= 1.0f || fromTop < 0.0f || fromTop >= tape.height) return float4(0.0f);
      float pixelRow = clamp(floor(fromTop), 0.0f, tape.height - 1.0f);
      // The half holds a row on an exact multiple above a float's rounding, as virtualRow's integer divide reads it.
      uint row = uint(floor((pixelRow * VHS_ROWS + 0.5f) / tape.height));
      float x = clamp(u, 0.0f, 1.0f);
      float shifted = u - vhsRowShift(tape, row);

      float sharp = vhsLumaBlur(frame, tape, shifted, at.y, tape.lumaSigma);
      float wide = vhsLumaBlur(frame, tape, shifted, at.y, tape.wideSigma);
      float2 chroma = vhsChromaBlur(frame, tape, shifted - tape.chromaShift, at.y, tape.chromaSigma);

      float band = vhsTracking(tape, row);
      float drop = vhsDropout(tape, row, x);
      uint chromaRow = row / ${CHROMA_NOISE_ROWS}u;
      float chromaCell = ${metal(STREAK * CHROMA_COARSENESS)};
      float chromaGain = (1.0f - ${metal(TRACK_CHROMA_LOSS)} * band) * (1.0f - drop) * (1.0f - tape.desaturation);

      float luma = sharp + tape.haloGain * (sharp - wide);
      luma += tape.lumaNoise * vhsStreak(tape, ${PASS_LUMA_NOISE}u, row, x, ${metal(STREAK)});
      luma += tape.trackNoise * band * vhsStreak(tape, ${PASS_TRACK_NOISE}u, row, x, ${metal(TRACK_NOISE_CELL)});
      luma += (1.0f - luma) * drop;
      luma = tape.lift + luma * (1.0f - tape.lift);
      float cbNoise = tape.chromaNoise * vhsStreak(tape, ${PASS_CB_NOISE}u, chromaRow, x, chromaCell);
      float crNoise = tape.chromaNoise * vhsStreak(tape, ${PASS_CR_NOISE}u, chromaRow, x, chromaCell);
      float cb = (chroma.x + cbNoise) * chromaGain;
      float cr = (chroma.y + crNoise) * chromaGain;

      float alpha = vhsRead(frame, tape, shifted, at.y).a;
      return float4(clamp(vhsYccToRgb(float3(luma, cb, cr)), 0.0f, 1.0f) * alpha, alpha);
    }
  }}
  """.trimIndent()

private fun tapTable(
  taps: Int,
  step: Double,
): String = (0..taps).joinToString { metal(VhsSignal.tapWeight(it, step)) }

private fun metal(value: Double): String = "${value}f"
