package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL

/**
 * The name of the Core Image kernel in [glitchMetal].
 */
internal const val GLITCH_KERNEL: String = "filmstripGlitch"

/**
 * Metal source for the Core Image kernel [GLITCH_KERNEL], the same steps [GlitchShader] takes.
 *
 * The row shift is [GLITCH_ROW_SHIFT], the text the GLSL fragment compiles too. Everything an intensity or a frame
 * changes is a kernel argument. The kernel reads the frame's own codes, premultiplied the way Core Image holds them,
 * and returns them that way. Alpha is read where green is, so it moves with the row, and no colour rises above it.
 */
internal fun glitchMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

$HASH_MSL

  float glitchDraw(uint seed, uint pass, uint tick, uint cell) { return filterDraw(seed, pass, tick, cell); }

$GLITCH_ROW_SHIFT

  float4 glitchRead(coreimage::sampler frame, float4 rect, float x, float y) {
    return frame.sample(frame.transform(float2(rect.x + clamp(x, 0.0f, 1.0f) * rect.z, y)));
  }

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $GLITCH_KERNEL(
      sampler frame,
      float4 rect,
      float2 clock,
      float4 shifts,
      destination dest
    ) {
      float2 at = dest.coord();
      float u = (at.x - rect.x) / rect.z;
      float fromTop = rect.y + rect.w - at.y;
      // Core Image runs a kernel past its extent when a later composite asks, and the frame is held at its edge there.
      if (u < 0.0f || u >= 1.0f || fromTop < 0.0f || fromTop >= rect.w) return float4(0.0f);
      float pixelRow = clamp(floor(fromTop), 0.0f, rect.w - 1.0f);
      // The half holds a row on an exact multiple above a float's rounding, as virtualRow's integer divide reads it.
      uint row = uint(floor((pixelRow * ${GLITCH_ROWS}.0f + 0.5f) / rect.w));
      uint seed = uint(clock.x + 0.5f);
      uint tick = uint(clock.y + 0.5f);
      float shifted = u - glitchRowShift(seed, tick, row, shifts.x, shifts.z, shifts.w);
      float4 green = glitchRead(frame, rect, shifted, at.y);
      float red = glitchRead(frame, rect, shifted - shifts.y, at.y).r;
      float blue = glitchRead(frame, rect, shifted + shifts.y, at.y).b;
      // Red and blue come from beside the alpha, so they are held under it to stay premultiplied beside a clear bar.
      return float4(min(float3(red, green.g, blue), green.a), green.a);
    }
  }}
  """.trimIndent()
