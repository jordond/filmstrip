package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.spatial.BOX_DOWN_MSL

/**
 * The name of the kernel in [filmMetal] that reads the frame moved by [FilmSignal.weaveAt].
 */
internal const val FILM_WEAVE_KERNEL: String = "filmstripFilmWeave"

/**
 * The name of the kernel in [filmMetal] that boxes the frame down with [BOX_DOWN_MSL] and draws the halation's
 * response.
 */
internal const val FILM_RESPONSE_KERNEL: String = "filmstripFilmResponse"

/**
 * The name of the kernel in [filmMetal] that adds the glow, the flicker, the grain, the dust and scratches and the gate
 * to the frame.
 */
internal const val FILM_GRADE_KERNEL: String = "filmstripFilmGrade"

/**
 * Metal source for the Core Image kernels of a [Film], the same steps [FilmShader] takes.
 *
 * Every fixed number is written in from [FilmSignal]'s constants, and everything a control or a frame changes is a
 * kernel argument. The kernels read the frame's own codes, premultiplied the way Core Image holds them, so a clear bar
 * gives the response no light, and the grade returns them premultiplied with the alpha it read. The weave reads
 * between the four pixels around each moved read, premultiplied as they are.
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
$FILM_GATE_MSL

${BOX_DOWN_MSL.prependIndent("  ")}

  float4 filmPixel(coreimage::sampler frame, float2 corner) {
    return frame.sample(frame.transform(corner + 0.5f));
  }

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $FILM_WEAVE_KERNEL(
      sampler frame,
      float4 extent,
      float3 weave,
      destination dest
    ) {
      float2 offset = weaveAt(uint(weave.x + 0.5f), uint(weave.y + 0.5f), weave.z);
      float2 at = dest.coord() + float2(offset.x * extent.z, -offset.y * extent.w) - 0.5f;
      float2 corner = floor(at);
      float2 t = at - corner;
      float4 low = mix(filmPixel(frame, corner), filmPixel(frame, corner + float2(1.0f, 0.0f)), t.x);
      float4 high = mix(filmPixel(frame, corner + float2(0.0f, 1.0f)), filmPixel(frame, corner + 1.0f), t.x);
      return mix(low, high, t.y);
    }

    [[stitchable]] float4 $FILM_RESPONSE_KERNEL(
      sampler frame,
      float4 extent,
      float3 blur,
      destination dest
    ) {
      float3 boxed = boxDown(frame, extent, blur, dest.coord()).rgb;
      float luma = dot(boxed, FILM_LUMA);
      float t = clamp((luma - ${metal(HALATION_LOW)}) / ${metal(HALATION_HIGH - HALATION_LOW)}, 0.0f, 1.0f);
      return float4(t * t * (3.0f - 2.0f * t) * FILM_TINT, 1.0f);
    }

    [[stitchable]] float4 $FILM_GRADE_KERNEL(
      sampler frame,
      sampler glow,
      float4 extent,
      float4 blur,
      float4 grain,
      float3 look,
      destination dest
    ) {
      float2 p = dest.coord();
      float4 held = frame.sample(frame.transform(p));
      float3 rgb = held.a > 0.0f ? held.rgb / held.a : held.rgb;
      if (blur.z > 0.0f) {
        float2 read = (p - extent.xy) / extent.zw * blur.xy;
        rgb = min(rgb + blur.z * glow.sample(glow.transform(read)).rgb, float3(1.0f));
      }
      uint seed = uint(grain.x + 0.5f);
      uint tick = uint(grain.y + 0.5f);
      uint span = uint(grain.z + 0.5f);
      uint x = uint(floor(p.x - extent.x));
      uint y = uint(extent.w - 1.0f - floor(p.y - extent.y));
      uint cell = (y * ${GRAIN_CELL_SCALE}u / span) * ${GRAIN_CELLS_ACROSS}u + x * ${GRAIN_CELL_SCALE}u / span;
      rgb = min(rgb * flickerAt(seed, tick, look.x), float3(1.0f));
      if (blur.w > 0.0f) {
        float first = filterDraw(seed, ${PASS_GRAIN_FIRST}u, tick, cell);
        float value = first + filterDraw(seed, ${PASS_GRAIN_SECOND}u, tick, cell) - 1.0f;
        float luma = dot(rgb, FILM_LUMA);
        float weight = max(${metal(GRAIN_WEIGHT_FLOOR)}, ${metal(GRAIN_WEIGHT_PEAK)} * luma * (1.0f - luma));
        rgb = clamp(rgb + blur.w * weight * value, 0.0f, 1.0f);
      }
      float across = (p.x - extent.x) / extent.z;
      float down = 1.0f - (p.y - extent.y) / extent.w;
      float mark = dustAt(seed, tick, cell, look.y) + scratchAt(seed, tick, across, look.y);
      float gate = gateMask(float2(across, down), extent.z / extent.w, look.z);
      rgb = clamp(rgb + mark, 0.0f, 1.0f) * gate;
      return float4(rgb * held.a, held.a);
    }
  }}
  """.trimIndent()

private fun metal(value: Double): String = "${value}f"
