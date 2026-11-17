package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR

/**
 * The name of the Core Image kernel in [tapePictureMetal].
 */
internal const val TAPE_PICTURE_KERNEL: String = "filmstripTapePicture"

/**
 * Metal source for the Core Image kernel [TAPE_PICTURE_KERNEL], the same steps [TapePictureShader] takes.
 *
 * Every fixed number is written in from [TapePictureSignal]'s constants, and everything an intensity or a frame changes
 * is a kernel argument. The kernel reads the frame's own codes, premultiplied the way Core Image holds them, and
 * returns them premultiplied. Alpha is read at the shifted position, so it moves with the row, and colour never raises
 * it.
 */
internal fun tapePictureMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  constant float TAPE_KR = ${metal(KR)};
  constant float TAPE_KB = ${metal(KB)};
  constant float TAPE_KG = 1.0f - TAPE_KR - TAPE_KB;
  constant float TAPE_TICK_RATE = ${metal(TICK_RATE)};
  constant float TAPE_ROWS = ${metal(VIRTUAL_ROWS.toDouble())};
  constant float TAPE_PI = 3.14159265358979f;
  constant float TAPE_TAU = 2.0f * TAPE_PI;
  constant float TAPE_HEAD_ROWS = ${metal(TapePictureSignal.headRows.toDouble())};
  constant float TAPE_LUMA_W[${LUMA_TAPS + 1}] = {${tapTable(LUMA_TAPS, LUMA_TAP_STEP)}};
  constant float TAPE_CHROMA_W[${CHROMA_TAPS + 1}] = {${tapTable(CHROMA_TAPS, CHROMA_TAP_STEP)}};

  struct Tape {
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
    float trackCentre;
    float trackStrength;
    float trackHalfHeight;
    float lift;
    float trackShift;
    float trackNoise;
    float trackSnow;
    float dropoutLength;
    float trackChromaLoss;
  };

$HASH_MSL

  float tapeSigned(uint seed, uint pass, uint tick, uint cell) {
    return filterDraw(seed, pass, tick, cell) * 2.0f - 1.0f;
  }

  float tapeSmooth(float from, float to, float value) {
    float t = clamp((value - from) / (to - from), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
  }

  float tapeTracking(Tape tape, uint row) {
    if (tape.trackStrength == 0.0f) return 0.0f;
    float d = abs((float(row) + 0.5f) / TAPE_ROWS - tape.trackCentre);
    d = min(d, 1.0f - d);
    return tape.trackStrength * (1.0f - tapeSmooth(0.0f, tape.trackHalfHeight, d));
  }

  float tapeRowShift(Tape tape, uint row) {
    float seconds = float(tape.tick) / TAPE_TICK_RATE;
    float y = (float(row) + 0.5f) / TAPE_ROWS;
    float phase = filterDraw(tape.seed, ${PASS_WAVE}u, 0u, 0u);
    float first = sin(TAPE_TAU * (${metal(WAVE_CYCLES)} * y + ${metal(WAVE_HZ)} * seconds + phase));
    float second = sin(TAPE_TAU * (${metal(WAVE_SECOND_CYCLES * WAVE_CYCLES)} * y +
      ${metal(WAVE_SECOND_HZ * WAVE_HZ)} * seconds + 2.0f * phase));
    float shift = tape.jitter * tapeSigned(tape.seed, ${PASS_JITTER}u, tape.tick, row);
    shift += tape.wave * (${metal(WAVE_FIRST)} * first + ${metal(1.0 - WAVE_FIRST)} * second);
    float intoBand = float(row) - (TAPE_ROWS - TAPE_HEAD_ROWS);
    if (intoBand >= 0.0f) {
      float wobble = 1.0f + ${metal(HEAD_WOBBLE)} * tapeSigned(tape.seed, ${PASS_HEAD}u, tape.tick, 0u);
      shift += tape.headShift * wobble * pow((intoBand + ${metal(HEAD_OFFSET)}) / TAPE_HEAD_ROWS, ${metal(HEAD_POWER)});
    }
    return shift + tape.trackShift * tapeTracking(tape, row) *
      tapeSigned(tape.seed, ${PASS_TRACK_SHIFT}u, tape.tick, row);
  }

  float tapeStreak(Tape tape, uint pass, uint row, float x, float cellLength) {
    float u = x / cellLength + filterDraw(tape.seed, pass, tape.tick, row);
    float f = fract(u);
    uint cell = uint(floor(u));
    float a = tapeSigned(tape.seed, pass, tape.tick, row + (cell + 1u) * ${ROW_CELL_STRIDE}u);
    float b = tapeSigned(tape.seed, pass, tape.tick, row + (cell + 2u) * ${ROW_CELL_STRIDE}u);
    return a + (b - a) * (f * f * (3.0f - 2.0f * f));
  }

  float tapeDropout(Tape tape, uint row, float x) {
    if (filterDraw(tape.seed, ${PASS_DROP_EVENT}u, tape.tick, row) >= tape.dropoutChance) return 0.0f;
    float start = filterDraw(tape.seed, ${PASS_DROP_POS}u, tape.tick, row);
    float len = tape.dropoutLength * (${metal(DROPOUT_LENGTH_MIN)} +
      ${metal(DROPOUT_LENGTH_SPREAD)} * filterDraw(tape.seed, ${PASS_DROP_LEN}u, tape.tick, row));
    float d = x - start;
    if (d < 0.0f || d > len) return 0.0f;
    float rise = tapeSmooth(0.0f, ${metal(DROPOUT_RISE)} * len, d);
    return rise * (1.0f - tapeSmooth(${metal(DROPOUT_FALL)} * len, len, d));
  }

  float3 tapeRgbToYcc(float3 c) {
    float y = TAPE_KR * c.r + TAPE_KG * c.g + TAPE_KB * c.b;
    return float3(y, (c.b - y) / (2.0f * (1.0f - TAPE_KB)), (c.r - y) / (2.0f * (1.0f - TAPE_KR)));
  }

  float3 tapeYccToRgb(float3 c) {
    float r = c.x + 2.0f * (1.0f - TAPE_KR) * c.z;
    float b = c.x + 2.0f * (1.0f - TAPE_KB) * c.y;
    return float3(r, (c.x - TAPE_KR * r - TAPE_KB * b) / TAPE_KG, b);
  }

  float4 tapeRead(coreimage::sampler frame, Tape tape, float x, float y) {
    return frame.sample(frame.transform(float2(tape.originX + clamp(x, 0.0f, 1.0f) * tape.width, y)));
  }

  // The frame in Y'CbCr at x, black past either edge the way an exposed line of tape is. The sampler mixes
  // premultiplied codes, so the mix is divided back to straight colour, black where nothing under it has alpha. An 8
  // bit source holds nothing past 0 to 1, so a working space value that does is cut back to read the same.
  float3 tapeOnTape(coreimage::sampler frame, Tape tape, float x, float y) {
    float inside = clamp(x * tape.width + 0.5f, 0.0f, 1.0f) * clamp((1.0f - x) * tape.width + 0.5f, 0.0f, 1.0f);
    float4 read = tapeRead(frame, tape, x, y);
    float3 straight = read.a > 0.0f ? read.rgb / read.a : float3(0.0f);
    return tapeRgbToYcc(clamp(straight, 0.0f, 1.0f)) * inside;
  }

  float tapeLumaBlur(coreimage::sampler frame, Tape tape, float x, float y, float sigma) {
    float sum = TAPE_LUMA_W[0] * tapeOnTape(frame, tape, x, y).x;
    float total = TAPE_LUMA_W[0];
    float gap = ${metal(LUMA_TAP_STEP)} * sigma;
    for (int i = 1; i <= $LUMA_TAPS; i++) {
      float offset = float(i) * gap;
      sum += TAPE_LUMA_W[i] * (tapeOnTape(frame, tape, x + offset, y).x + tapeOnTape(frame, tape, x - offset, y).x);
      total += 2.0f * TAPE_LUMA_W[i];
    }
    return sum / total;
  }

  float2 tapeChromaBlur(coreimage::sampler frame, Tape tape, float x, float y, float sigma) {
    float2 sum = TAPE_CHROMA_W[0] * tapeOnTape(frame, tape, x, y).yz;
    float total = TAPE_CHROMA_W[0];
    float gap = ${metal(CHROMA_TAP_STEP)} * sigma;
    for (int i = 1; i <= $CHROMA_TAPS; i++) {
      float offset = float(i) * gap;
      sum += TAPE_CHROMA_W[i] * (tapeOnTape(frame, tape, x + offset, y).yz + tapeOnTape(frame, tape, x - offset, y).yz);
      total += 2.0f * TAPE_CHROMA_W[i];
    }
    return sum / total;
  }

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $TAPE_PICTURE_KERNEL(
      sampler frame,
      float4 rect,
      float3 clock,
      float4 blur,
      float4 shifts,
      float4 noise,
      float4 track,
      float4 band,
      destination dest
    ) {
      Tape tape = {
        uint(clock.x + 0.5f), uint(clock.y + 0.5f),
        rect.x, rect.y, rect.z, rect.w,
        blur.x, blur.y, blur.z, blur.w,
        shifts.x, shifts.y, shifts.z, shifts.w,
        noise.x, noise.y, noise.z, noise.w,
        track.x, track.y, track.z, track.w,
        band.x, band.y, band.z, band.w,
        clock.z
      };
      float2 at = dest.coord();
      float u = (at.x - tape.originX) / tape.width;
      float fromTop = tape.originY + tape.height - at.y;
      // Core Image runs a kernel past its extent when a later composite asks, and the frame is held at its edge there.
      if (u < 0.0f || u >= 1.0f || fromTop < 0.0f || fromTop >= tape.height) return float4(0.0f);
      float pixelRow = clamp(floor(fromTop), 0.0f, tape.height - 1.0f);
      // The half holds a row on an exact multiple above a float's rounding, as virtualRow's integer divide reads it.
      uint row = uint(floor((pixelRow * TAPE_ROWS + 0.5f) / tape.height));
      float x = clamp(u, 0.0f, 1.0f);
      float shifted = u - tapeRowShift(tape, row);

      float sharp = tapeLumaBlur(frame, tape, shifted, at.y, tape.lumaSigma);
      float wide = tapeLumaBlur(frame, tape, shifted, at.y, tape.wideSigma);
      float2 chroma = tapeChromaBlur(frame, tape, shifted - tape.chromaShift, at.y, tape.chromaSigma);

      float weight = tapeTracking(tape, row);
      float drop = tapeDropout(tape, row, x);
      float snowWeight = tape.trackSnow * weight;
      float snow = 0.0f;
      if (snowWeight > 0.0f) {
        snow = clamp(0.5f + ${metal(SNOW_CONTRAST * 0.5)} *
          tapeStreak(tape, ${PASS_SNOW}u, row, x, ${metal(SNOW_CELL)}), 0.0f, 1.0f);
      }
      uint chromaRow = row / ${CHROMA_NOISE_ROWS}u;
      float chromaCell = ${metal(STREAK * CHROMA_COARSENESS)};
      float chromaGain = (1.0f - tape.trackChromaLoss * weight) * (1.0f - drop) * (1.0f - tape.desaturation) *
        (1.0f - snowWeight);

      float luma = sharp + tape.haloGain * (sharp - wide);
      luma += tape.lumaNoise * tapeStreak(tape, ${PASS_LUMA_NOISE}u, row, x, ${metal(STREAK)});
      luma += tape.trackNoise * weight * tapeStreak(tape, ${PASS_TRACK_NOISE}u, row, x, ${metal(TRACK_NOISE_CELL)});
      luma += (1.0f - luma) * drop;
      luma += (snow - luma) * snowWeight;
      luma = tape.lift + luma * (1.0f - tape.lift);
      float cbNoise = tape.chromaNoise * tapeStreak(tape, ${PASS_CB_NOISE}u, chromaRow, x, chromaCell);
      float crNoise = tape.chromaNoise * tapeStreak(tape, ${PASS_CR_NOISE}u, chromaRow, x, chromaCell);
      float cb = (chroma.x + cbNoise) * chromaGain;
      float cr = (chroma.y + crNoise) * chromaGain;

      float alpha = tapeRead(frame, tape, shifted, at.y).a;
      return float4(clamp(tapeYccToRgb(float3(luma, cb, cr)), 0.0f, 1.0f) * alpha, alpha);
    }
  }}
  """.trimIndent()

private fun tapTable(
  taps: Int,
  step: Double,
): String = (0..taps).joinToString { metal(TapePictureSignal.tapWeight(it, step)) }

private fun metal(value: Double): String = "${value}f"
