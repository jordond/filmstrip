package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.effects.color.glsl
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdr.KneePiece
import dev.jordond.filmstrip.transform.internal.hdr.PqKnee
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.RgbMatrix
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_BT2020_TO_BT709
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_HLG_EXPONENT
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_LUMA_WEIGHTS
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_MASTERING_PEAK_NITS
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_OETF_GAMMA
import dev.jordond.filmstrip.transform.internal.hdr.TONE_MAP_PQ_GAIN_SCALE

/**
 * GLSL for `vec3 toneMappedSdr(vec3 signal)`, the shared tone curve on a BT.2020 signal of this transfer.
 *
 * It takes the same steps as `toneMappedSdr` in the same order, and every figure in it is read off the curve's own
 * pieces. The result is the encoded BT.709 SDR signal, zero to one on each channel.
 */
internal fun toneMapGlsl(transfer: HdrTransfer): String =
  """
  const mat3 TONE_MAP_BT2020_TO_BT709 = ${TONE_MAP_BT2020_TO_BT709.glsl()};
  ${toneMapLinearGlsl(transfer)}
  ${toneMapGainGlsl(transfer)}
  vec3 toneMappedSdr(vec3 signal) {
    vec3 linear = toneMapLinear(clamp(signal, 0.0, 1.0));
    vec3 sdr = TONE_MAP_BT2020_TO_BT709 * (linear * toneMapGain(linear));
    return pow(clamp(sdr, 0.0, 1.0), vec3(1.0 / ${TONE_MAP_OETF_GAMMA.glsl()}));
  }
  """.trimIndent()

private fun toneMapLinearGlsl(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> {
      """
      ${eotfGlsl(HdrTransfer.Pq)}
      vec3 toneMapLinear(vec3 signal) {
        return eotf(signal) / ${PQ_PEAK_NITS.glsl()};
      }
      """.trimIndent()
    }
    HdrTransfer.Hlg -> {
      """
      $HLG_SCENE_GLSL
      vec3 toneMapLinear(vec3 signal) {
        return hlgScene(signal);
      }
      """.trimIndent()
    }
  }

// The PQ gain is the knee read on the green channel, and black green takes the straight part's slope.
private fun toneMapGainGlsl(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> {
      """
      $PQ_KNEE_GLSL
      float toneMapGain(vec3 linear) {
        float nits = linear.g * ${PQ_PEAK_NITS.glsl()};
        float knee = nits > 0.0 ? pqKneeNits(nits) / nits : ${PqKnee.slope.glsl()};
        return knee * ${TONE_MAP_PQ_GAIN_SCALE.glsl()};
      }
      """.trimIndent()
    }
    HdrTransfer.Hlg -> {
      """
      float toneMapGain(vec3 linear) {
        float luminance = max(dot(linear, ${TONE_MAP_LUMA_WEIGHTS.glsl()}), 0.0);
        return pow(luminance, ${TONE_MAP_HLG_EXPONENT.glsl()});
      }
      """.trimIndent()
    }
  }

private val PQ_KNEE_GLSL =
  with(PqKnee) {
    """
    float pqKneeNits(float nits) {
      float level = clamp(nits, 0.0, ${TONE_MAP_MASTERING_PEAK_NITS.glsl()});
      if (level < ${x1.glsl()}) return level * ${slope.glsl()};
      if (level < ${x2.glsl()}) {
        ${lower.glsl()}
      }
      ${upper.glsl()}
    }
    """.trimIndent()
  }

// The piece as KneePiece.at writes it, a statement that returns its level for `level`.
private fun KneePiece.glsl(): String =
  "float t = (level - ${start.glsl()}) / ${width.glsl()}; " +
    "return (${from.glsl()} * (1.0 + 2.0 * t) + ${startRise.glsl()} * t) * (1.0 - t) * (1.0 - t) + " +
    "(${to.glsl()} * (3.0 - 2.0 * t) + ${endRise.glsl()} * (t - 1.0)) * t * t;"

private fun Rgb.glsl(): String = "vec3(${r.glsl()}, ${g.glsl()}, ${b.glsl()})"

// GLSL's mat3 takes its columns in turn, where the shared matrix is stored as its rows.
private fun RgbMatrix.glsl(): String =
  "mat3(" +
    listOf(Rgb::r, Rgb::g, Rgb::b).joinToString(", ") { column ->
      "${column(red).glsl()}, ${column(green).glsl()}, ${column(blue).glsl()}"
    } + ")"
