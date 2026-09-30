package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.color.hdrColorMatrixUniforms
import dev.jordond.filmstrip.media.BT2020_LUMA_B
import dev.jordond.filmstrip.media.BT2020_LUMA_G
import dev.jordond.filmstrip.media.BT2020_LUMA_R
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_B
import dev.jordond.filmstrip.media.HLG_C
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_C1
import dev.jordond.filmstrip.media.PQ_C2
import dev.jordond.filmstrip.media.PQ_C3
import dev.jordond.filmstrip.media.PQ_M1
import dev.jordond.filmstrip.media.PQ_M2
import dev.jordond.filmstrip.media.PQ_PEAK_NITS

/**
 * Where [nits] of linear BT.2020 display light sit in the compositor's float buffer on a kept grade, with reference
 * white at one.
 *
 * The buffer holds linear display light on both transfers, so this is the light itself and a blend in the buffer is a
 * blend in display light.
 */
internal fun compositorLightOf(nits: FloatArray): FloatArray = FloatArray(3) { nits[it] / HDR_REFERENCE_WHITE_NITS }

/**
 * The figures the compositor grades light of this transfer through, for a clip and an overlay alike.
 *
 * PQ is graded on the buffer's own light. HLG is graded on scene light normalised against [HLG_NOMINAL_PEAK_NITS],
 * which [gradeLightGlsl] turns the buffer's light into and back, so the matrix meets the per channel reading media3
 * and ffmpeg grade HLG with.
 */
internal fun HdrTransfer.compositorGradeUniforms(): HdrColorMatrixUniforms =
  when (this) {
    HdrTransfer.Pq -> {
      hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)
    }
    HdrTransfer.Hlg -> {
      hdrColorMatrixUniforms(white = HDR_REFERENCE_WHITE_NITS / HLG_NOMINAL_PEAK_NITS, holdsSceneLight = true)
    }
  }

/**
 * GLSL for `vec3 eotf(vec3 signal)`, which decodes a signal of this transfer to linear display light in cd/m2.
 */
internal fun eotfGlsl(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> PQ_EOTF_GLSL
    HdrTransfer.Hlg -> "$HLG_OOTF_GLSL\n$HLG_EOTF_GLSL"
  }

/**
 * GLSL for `vec3 oetf(vec3 nits)`, which encodes linear display light in cd/m2 as a signal of this transfer.
 */
internal fun oetfGlsl(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> PQ_OETF_GLSL
    HdrTransfer.Hlg -> "$HLG_OOTF_GLSL\n$HLG_OETF_GLSL"
  }

/**
 * GLSL for `vec3 toGradeLight(vec3 light)` and `vec3 fromGradeLight(vec3 graded)`, which carry the buffer's light
 * into the light [compositorGradeUniforms] describes and back.
 */
internal fun gradeLightGlsl(transfer: HdrTransfer): String =
  when (transfer) {
    HdrTransfer.Pq -> PQ_GRADE_LIGHT_GLSL
    HdrTransfer.Hlg -> "$HLG_OOTF_GLSL\n$HLG_GRADE_LIGHT_GLSL"
  }

/**
 * This figure as a GLSL float literal.
 *
 * A whole number renders without a decimal point on the js target, where a Kotlin Float is a
 * JavaScript number, and GLSL will not multiply a vector by an integer literal. Spelling the point
 * back on keeps one shader source across both targets.
 */
internal fun Number.glsl(): String {
  val text = toString()

  return if (text.any { it == '.' || it == 'e' || it == 'E' }) text else "$text.0"
}

private val PQ_EOTF_GLSL =
  """
  vec3 eotf(vec3 signal) {
    vec3 encoded = pow(max(signal, 0.0), vec3(1.0 / ${PQ_M2.glsl()}));
    vec3 numerator = max(encoded - ${PQ_C1.glsl()}, 0.0);
    vec3 denominator = ${PQ_C2.glsl()} - ${PQ_C3.glsl()} * encoded;
    return pow(numerator / denominator, vec3(1.0 / ${PQ_M1.glsl()})) * ${PQ_PEAK_NITS.glsl()};
  }
  """.trimIndent()

private val PQ_OETF_GLSL =
  """
  vec3 oetf(vec3 nits) {
    vec3 y = pow(clamp(nits / ${PQ_PEAK_NITS.glsl()}, 0.0, 1.0), vec3(${PQ_M1.glsl()}));
    return pow((${PQ_C1.glsl()} + ${PQ_C2.glsl()} * y) / (1.0 + ${PQ_C3.glsl()} * y), vec3(${PQ_M2.glsl()}));
  }
  """.trimIndent()

private val PQ_GRADE_LIGHT_GLSL =
  """
  vec3 toGradeLight(vec3 light) { return light; }
  vec3 fromGradeLight(vec3 graded) { return graded; }
  """.trimIndent()

// HLG's opto-optical transfer, driven by the colour's luminance. hlgSceneFromDisplay is hlgSceneFromNits spelled in
// GLSL, and hlgDisplayFromScene is its inverse. Scene light is normalised against the nominal peak. A black pixel is
// answered outright, since GLSL leaves the power of zero to the driver.
private val HLG_OOTF_GLSL =
  """
  const vec3 HLG_LUMA = vec3(${BT2020_LUMA_R.glsl()}, ${BT2020_LUMA_G.glsl()}, ${BT2020_LUMA_B.glsl()});
  const float HLG_GAMMA = ${HLG_SYSTEM_GAMMA.glsl()};
  vec3 hlgSceneFromDisplay(vec3 nits) {
    vec3 display = max(nits, 0.0) / ${HLG_NOMINAL_PEAK_NITS.glsl()};
    float luminance = dot(display, HLG_LUMA);
    if (luminance <= 0.0) return vec3(0.0);
    return display / pow(luminance, (HLG_GAMMA - 1.0) / HLG_GAMMA);
  }
  vec3 hlgDisplayFromScene(vec3 scene) {
    vec3 light = max(scene, 0.0);
    float luminance = dot(light, HLG_LUMA);
    if (luminance <= 0.0) return vec3(0.0);
    return light * pow(luminance, HLG_GAMMA - 1.0) * ${HLG_NOMINAL_PEAK_NITS.glsl()};
  }
  """.trimIndent()

private val HLG_EOTF_GLSL =
  """
  vec3 eotf(vec3 signal) {
    vec3 low = signal * signal / 3.0;
    vec3 high = (exp((signal - ${HLG_C.glsl()}) / ${HLG_A.glsl()}) + ${HLG_B.glsl()}) / 12.0;
    return hlgDisplayFromScene(mix(low, high, step(0.5, signal)));
  }
  """.trimIndent()

// The log arm has to be guarded rather than left to the clamp: mix evaluates both arms, and the
// dark one would take the log of a negative and carry a NaN across the blend.
private val HLG_OETF_GLSL =
  """
  vec3 oetf(vec3 nits) {
    vec3 scene = hlgSceneFromDisplay(nits);
    vec3 low = sqrt(3.0 * scene);
    vec3 high = ${HLG_A.glsl()} * log(max(12.0 * scene - ${HLG_B.glsl()}, 1e-6)) + ${HLG_C.glsl()};
    return clamp(mix(low, high, step(1.0 / 12.0, scene)), 0.0, 1.0);
  }
  """.trimIndent()

private val HLG_GRADE_LIGHT_GLSL =
  """
  vec3 toGradeLight(vec3 light) {
    return hlgSceneFromDisplay(light * ${HDR_REFERENCE_WHITE_NITS.glsl()});
  }
  vec3 fromGradeLight(vec3 graded) {
    return hlgDisplayFromScene(graded) / ${HDR_REFERENCE_WHITE_NITS.glsl()};
  }
  """.trimIndent()
