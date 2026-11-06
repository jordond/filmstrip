package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.color.HLG_A
import dev.jordond.filmstrip.color.HLG_B
import dev.jordond.filmstrip.color.HLG_C
import dev.jordond.filmstrip.color.PQ_C1
import dev.jordond.filmstrip.color.PQ_C2
import dev.jordond.filmstrip.color.PQ_C3
import dev.jordond.filmstrip.color.PQ_M1
import dev.jordond.filmstrip.color.PQ_M2
import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdr.KneePiece
import dev.jordond.filmstrip.transform.internal.hdr.PqKnee
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_BT2020_TO_BT709
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_HLG_EXPONENT
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_LUMA_WEIGHTS
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_MASTERING_PEAK_NITS
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_OETF_GAMMA
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_PQ_GAIN_SCALE

/**
 * Metal source for the Core Image colour kernel [TONE_MAP_KERNEL], the shared tone curve on a BT.2020 signal of this
 * transfer.
 *
 * It takes the same steps as `toneMappedSdr` in the same order, and every figure in it is read off the curve's own
 * pieces. The kernel reads raw signal codes and returns the encoded BT.709 SDR signal, zero to one on each channel.
 */
internal fun toneMapMetal(transfer: HdrTransfer): String =
  """
  #include <metal_stdlib>
  #include <CoreImage/CoreImage.h>
  using namespace metal;
  ${toneMapLinearMetal(transfer)}
  ${toneMapGainMetal(transfer)}
  extern "C" { namespace coreimage {
    [[stitchable]] float4 $TONE_MAP_KERNEL(sample_t pixel) {
      float3 linear = toneMapLinear(clamp(pixel.rgb, 0.0f, 1.0f));
      float3 scaled = linear * toneMapGain(linear);
      float3 sdr = float3(
        dot(${TONE_MAP_BT2020_TO_BT709.red.metal()}, scaled),
        dot(${TONE_MAP_BT2020_TO_BT709.green.metal()}, scaled),
        dot(${TONE_MAP_BT2020_TO_BT709.blue.metal()}, scaled));
      return float4(pow(clamp(sdr, 0.0f, 1.0f), float3(1.0f / ${TONE_MAP_OETF_GAMMA.metal()})), pixel.a);
    }
  }}
  """.trimIndent()

/**
 * The name of the kernel [toneMapMetal] declares.
 */
internal const val TONE_MAP_KERNEL = "toneMappedSdr"

private fun toneMapLinearMetal(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> PQ_LINEAR_METAL
    HdrTransfer.Hlg -> HLG_LINEAR_METAL
  }

private fun toneMapGainMetal(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> PQ_GAIN_METAL
    HdrTransfer.Hlg -> HLG_GAIN_METAL
  }

private val PQ_LINEAR_METAL =
  """
  static float3 toneMapLinear(float3 signal) {
    float3 encoded = pow(signal, float3(1.0f / ${PQ_M2.metal()}));
    float3 numerator = max(encoded - ${PQ_C1.metal()}, 0.0f);
    float3 denominator = ${PQ_C2.metal()} - ${PQ_C3.metal()} * encoded;
    return pow(numerator / denominator, float3(1.0f / ${PQ_M1.metal()}));
  }
  """.trimIndent()

private val HLG_LINEAR_METAL =
  """
  static float3 toneMapLinear(float3 signal) {
    float3 low = signal * signal / 3.0f;
    float3 high = (exp((signal - ${HLG_C.metal()}) / ${HLG_A.metal()}) + ${HLG_B.metal()}) / 12.0f;
    return mix(low, high, step(0.5f, signal));
  }
  """.trimIndent()

private val PQ_GAIN_METAL =
  with(PqKnee) {
    """
    static float pqKneeNits(float nits) {
      float level = clamp(nits, 0.0f, ${TONE_MAP_MASTERING_PEAK_NITS.metal()});
      if (level < ${x1.metal()}) return level * ${slope.metal()};
      if (level < ${x2.metal()}) {
        ${lower.metal()}
      }
      ${upper.metal()}
    }
    static float toneMapGain(float3 linear) {
      float nits = linear.g * ${PQ_PEAK_NITS.metal()};
      float knee = nits > 0.0f ? pqKneeNits(nits) / nits : ${slope.metal()};
      return knee * ${TONE_MAP_PQ_GAIN_SCALE.metal()};
    }
    """.trimIndent()
  }

private val HLG_GAIN_METAL =
  """
  static float toneMapGain(float3 linear) {
    float luminance = max(dot(linear, ${TONE_MAP_LUMA_WEIGHTS.metal()}), 0.0f);
    return pow(luminance, ${TONE_MAP_HLG_EXPONENT.metal()});
  }
  """.trimIndent()

private fun KneePiece.metal(): String =
  "float t = (level - ${start.metal()}) / ${width.metal()}; " +
    "return (${from.metal()} * (1.0f + 2.0f * t) + ${startRise.metal()} * t) * (1.0f - t) * (1.0f - t) + " +
    "(${to.metal()} * (3.0f - 2.0f * t) + ${endRise.metal()} * (t - 1.0f)) * t * t;"

private fun Rgb.metal(): String = "float3(${r.metal()}, ${g.metal()}, ${b.metal()})"

private fun Number.metal(): String = "${this}f"
