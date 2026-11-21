package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effects.filters.internal.HASH_MSL
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.spatial.BOX_DOWN_MSL

/**
 * The name of the kernel in [digicamMetal] that boxes the frame down with [BOX_DOWN_MSL] to the size its blur runs at.
 */
internal const val DIGICAM_DOWN_KERNEL: String = "filmstripDigicamDown"

/**
 * The name of the kernel in [digicamMetal] that blurs along one axis by the weights of a [BlurPlan].
 */
internal const val DIGICAM_BLUR_KERNEL: String = "filmstripDigicamBlur"

/**
 * The name of the kernel in [digicamMetal] that sharpens the frame against its blur, clips it and adds the noise.
 */
internal const val DIGICAM_KERNEL: String = "filmstripDigicam"

/**
 * How many four float arguments carry a [BlurPlan]'s weights to [DIGICAM_BLUR_KERNEL], enough for its centre and
 * [BlurPlan.MAX_TAPS] taps.
 */
internal const val DIGICAM_WEIGHT_VECTORS: Int = (BlurPlan.MAX_TAPS + 1 + 3) / 4

/**
 * Metal source for the Core Image kernel named [kernel], one of the steps [DigicamShader] takes.
 *
 * Every fixed number is written in from [DigicamSignal]'s constants, and everything a control or a frame changes is a
 * kernel argument. The blur reads its taps one by one, so it lands on the plan's kernel at a sigma under a pixel. Core
 * Image counts rows up from the bottom, so the last kernel turns them to count from the top the way the signal does.
 * It reads the frame's own codes premultiplied, sharpens the straight colour against the blur, and returns it
 * premultiplied by the alpha it read, so a clear bar stays clear.
 *
 * Each kernel has a source of its own. Core Image joins a clamp to the kernel that reads it, and between two kernels
 * of one source it hands the joined program the other kernel's arguments and draws nothing.
 *
 * @throws IllegalArgumentException When [kernel] is not a kernel of a [Digicam].
 */
internal fun digicamMetal(kernel: String): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;

${
    when (kernel) {
      DIGICAM_DOWN_KERNEL -> DOWN_MSL
      DIGICAM_BLUR_KERNEL -> BLUR_MSL
      DIGICAM_KERNEL -> LOOK_MSL
      else -> throw IllegalArgumentException("$kernel is not a digicam kernel.")
    }
  }
  """.trimIndent()

// Each source carries the kernel sources' own two spaces, so trimIndent still lands the includes first.
private val DOWN_MSL: String =
  """
${BOX_DOWN_MSL.prependIndent("  ")}

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $DIGICAM_DOWN_KERNEL(sampler frame, float4 extent, float3 small, destination dest) {
      return float4(boxDown(frame, extent, small, dest.coord()).rgb, 1.0f);
    }
  }}
  """.trim('\n')

// The four weights that hold tap i, picked from the kernel's weight arguments.
private val WEIGHTS_OF_TAP: String =
  (1 until DIGICAM_WEIGHT_VECTORS).joinToString("") { "i < ${it * 4} ? w${it - 1} : " } +
    "w${DIGICAM_WEIGHT_VECTORS - 1}"

private val BLUR_MSL: String =
  """
  extern "C" { namespace coreimage {
    [[stitchable]] float4 $DIGICAM_BLUR_KERNEL(
      sampler image,
      float3 run,
${(0 until DIGICAM_WEIGHT_VECTORS).joinToString("\n") { "      float4 w$it," }}
      destination dest
    ) {
      float2 p = dest.coord();
      int taps = int(run.z + 0.5f);
      float3 sum = w0.x * image.sample(image.transform(p)).rgb;
      for (int i = 1; i <= taps; i++) {
        float2 reach = float(i) * run.xy;
        float3 before = image.sample(image.transform(p - reach)).rgb;
        float3 after = image.sample(image.transform(p + reach)).rgb;
        float4 four = $WEIGHTS_OF_TAP;
        sum += four[i % 4] * (before + after);
      }
      return float4(sum, 1.0f);
    }
  }}
  """.trim('\n')

private val LOOK_MSL: String =
  """
  constant float DIGICAM_KR = ${KR}f;
  constant float DIGICAM_KB = ${KB}f;
  constant float3 DIGICAM_LUMA = float3(DIGICAM_KR, 1.0f - DIGICAM_KR - DIGICAM_KB, DIGICAM_KB);

${HASH_MSL.trim('\n')}

  extern "C" { namespace coreimage {
    [[stitchable]] float4 $DIGICAM_KERNEL(
      sampler frame,
      sampler blurred,
      float4 extent,
      float2 small,
      float3 look,
      float3 noise,
      destination dest
    ) {
      float2 p = dest.coord();
      float4 held = frame.sample(frame.transform(p));
      float3 straight = held.a > 0.0f ? held.rgb / held.a : float3(0.0f);
      float2 read = (p - extent.xy) / extent.zw * small;
      float3 soft = blurred.sample(blurred.transform(read)).rgb;
      float3 sharpened = clamp(straight + look.x * (straight - soft), 0.0f, 1.0f);
      float3 pushed = min(sharpened * look.y, float3(1.0f));
      uint seed = uint(noise.x + 0.5f);
      uint tick = uint(noise.y + 0.5f);
      uint side = uint(noise.z + 0.5f);
      float column = clamp(floor(p.x - extent.x), 0.0f, extent.z - 1.0f);
      float row = clamp(extent.w - 1.0f - floor(p.y - extent.y), 0.0f, extent.w - 1.0f);
      uint cell = (uint(row) / side) * ${NOISE_CELLS_ACROSS}u + uint(column) / side;
      float left = 1.0f - dot(pushed, DIGICAM_LUMA);
      float weight = look.z * left * left;
      float moveBlue = ${CB_TO_BLUE}f * weight * (filterDraw(seed, ${PASS_NOISE_CB}u, tick, cell) * 2.0f - 1.0f);
      float moveRed = ${CR_TO_RED}f * weight * (filterDraw(seed, ${PASS_NOISE_CR}u, tick, cell) * 2.0f - 1.0f);
      float moveGreen = -(DIGICAM_KR * moveRed + DIGICAM_KB * moveBlue) / (1.0f - DIGICAM_KR - DIGICAM_KB);
      float3 noised = clamp(pushed + float3(moveRed, moveGreen, moveBlue), 0.0f, 1.0f);
      return float4(noised * held.a, held.a);
    }
  }}
  """.trim('\n')
