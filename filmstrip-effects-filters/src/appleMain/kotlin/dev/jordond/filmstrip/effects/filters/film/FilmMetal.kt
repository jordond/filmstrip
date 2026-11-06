package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR

/**
 * The name of the kernel in [filmMetal] that boxes the frame down and draws the halation's response.
 */
internal const val FILM_RESPONSE_KERNEL: String = "filmstripFilmResponse"

/**
 * The name of the kernel in [filmMetal] that adds the glow and the grain to the frame.
 */
internal const val FILM_GRADE_KERNEL: String = "filmstripFilmGrade"

/**
 * Metal source for the Core Image kernels of a [Film], the same steps [FilmShader] takes.
 *
 * Every fixed number is written in from [FilmSignal]'s constants, and everything a control or a frame changes is a
 * kernel argument. The kernels read the frame's own codes, premultiplied the way Core Image holds them, so a clear bar
 * gives the response no light, and the grade returns them premultiplied with the alpha it read.
 */
internal fun filmMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  constant float3 FILM_LUMA = float3(${metal(KR)}, ${metal(1.0 - KR - KB)}, ${metal(KB)});
  constant float3 FILM_TINT = float3(${metal(
    HALATION_TINT_RED,
  )}, ${metal(HALATION_TINT_GREEN)}, ${metal(HALATION_TINT_BLUE)});

$HASH_MSL

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $FILM_RESPONSE_KERNEL(
      sampler frame,
      float4 extent,
      float3 blur,
      destination dest
    ) {
      float2 target = blur.xy;
      float2 size = extent.zw;
      int box = int(blur.z + 0.5f);
      float2 at = dest.coord() / target;
      float3 sum = float3(0.0f);
      for (int y = 0; y < box; y++) {
        for (int x = 0; x < box; x++) {
          float2 tap = (float2(float(x), float(y)) + 0.5f) / blur.z - 0.5f;
          float2 read = extent.xy + (at + tap / target) * size;
          sum += frame.sample(frame.transform(read)).rgb;
        }
      }
      float luma = dot(sum / (blur.z * blur.z), FILM_LUMA);
      float t = clamp((luma - ${metal(HALATION_LOW)}) / ${metal(HALATION_HIGH - HALATION_LOW)}, 0.0f, 1.0f);
      return float4(t * t * (3.0f - 2.0f * t) * FILM_TINT, 1.0f);
    }

    [[stitchable]] float4 $FILM_GRADE_KERNEL(
      sampler frame,
      sampler glow,
      float4 extent,
      float4 blur,
      float4 grain,
      destination dest
    ) {
      float2 p = dest.coord();
      float4 held = frame.sample(frame.transform(p));
      float3 rgb = held.a > 0.0f ? held.rgb / held.a : held.rgb;
      if (blur.z > 0.0f) {
        float2 read = (p - extent.xy) / extent.zw * blur.xy;
        rgb = min(rgb + blur.z * glow.sample(glow.transform(read)).rgb, float3(1.0f));
      }
      if (blur.w > 0.0f) {
        uint seed = uint(grain.x + 0.5f);
        uint tick = uint(grain.y + 0.5f);
        uint span = uint(grain.z + 0.5f);
        uint x = uint(floor(p.x - extent.x));
        uint y = uint(extent.w - 1.0f - floor(p.y - extent.y));
        uint cell = (y * ${GRAIN_CELL_SCALE}u / span) * ${GRAIN_CELLS_ACROSS}u + x * ${GRAIN_CELL_SCALE}u / span;
        float first = filterDraw(seed, ${PASS_GRAIN_FIRST}u, tick, cell);
        float value = first + filterDraw(seed, ${PASS_GRAIN_SECOND}u, tick, cell) - 1.0f;
        float luma = dot(rgb, FILM_LUMA);
        float weight = max(${metal(GRAIN_WEIGHT_FLOOR)}, ${metal(GRAIN_WEIGHT_PEAK)} * luma * (1.0f - luma));
        rgb = clamp(rgb + blur.w * weight * value, 0.0f, 1.0f);
      }
      return float4(rgb * held.a, held.a);
    }
  }}
  """.trimIndent()

private fun metal(value: Double): String = "${value}f"
