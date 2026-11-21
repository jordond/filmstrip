package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.spatial.BOX_DOWN_MSL

/**
 * The name of the kernel in [bloomMetal] that boxes the frame down with [BOX_DOWN_MSL] and draws the bloom's
 * response.
 */
internal const val BLOOM_RESPONSE_KERNEL: String = "filmstripBloomResponse"

/**
 * The name of the kernel in [bloomMetal] that screens the blurred light over the frame.
 */
internal const val BLOOM_SCREEN_KERNEL: String = "filmstripBloomScreen"

/**
 * Metal source for the Core Image kernels of a [Bloom], the same steps [BloomShader] takes.
 *
 * Every figure a control or a frame changes is a kernel argument. The kernels read the frame's own codes, premultiplied
 * the way Core Image holds them, so a clear bar gives the response no light, and the screen returns them premultiplied
 * with the alpha it read.
 */
internal fun bloomMetal(): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

  constant float3 BLOOM_LUMA = float3(${KR}f, ${1.0 - KR - KB}f, ${KB}f);

${BOX_DOWN_MSL.prependIndent("  ")}

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $BLOOM_RESPONSE_KERNEL(
      sampler frame,
      float4 extent,
      float3 blur,
      float2 knee,
      destination dest
    ) {
      float3 boxed = boxDown(frame, extent, blur, dest.coord()).rgb;
      float t = clamp((dot(boxed, BLOOM_LUMA) - knee.x) / (knee.y - knee.x), 0.0f, 1.0f);
      return float4(boxed * t * t * (3.0f - 2.0f * t), 1.0f);
    }

    [[stitchable]] float4 $BLOOM_SCREEN_KERNEL(
      sampler frame,
      sampler glow,
      float4 extent,
      float3 blur,
      destination dest
    ) {
      float2 p = dest.coord();
      float4 held = frame.sample(frame.transform(p));
      float3 rgb = held.a > 0.0f ? held.rgb / held.a : held.rgb;
      float2 read = (p - extent.xy) / extent.zw * blur.xy;
      float3 light = min(blur.z * glow.sample(glow.transform(read)).rgb, float3(1.0f));
      rgb = rgb + light * (1.0f - rgb);
      return float4(rgb * held.a, held.a);
    }
  }}
  """.trimIndent()
