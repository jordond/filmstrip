@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.HLG_A
import dev.jordond.filmstrip.color.HLG_B
import dev.jordond.filmstrip.color.HLG_C
import dev.jordond.filmstrip.color.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.color.PQ_C1
import dev.jordond.filmstrip.color.PQ_C2
import dev.jordond.filmstrip.color.PQ_C3
import dev.jordond.filmstrip.color.PQ_M1
import dev.jordond.filmstrip.color.PQ_M2
import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.effects.color.HLG_OOTF_GLSL
import dev.jordond.filmstrip.effects.color.displayLightGradeUniforms
import dev.jordond.filmstrip.effects.color.glsl
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Where [nits] of linear BT.2020 display light sit in the compositor's float buffer on a kept grade, with reference
 * white at one.
 *
 * The buffer holds linear display light on both transfers, so this is the light itself and a blend in the buffer is a
 * blend in display light.
 */
internal fun compositorLightOf(nits: FloatArray): FloatArray = FloatArray(3) { nits[it] / HDR_REFERENCE_WHITE_NITS }

/**
 * Sets the grade uniforms of [program], which has to be the program in use, to the figures
 * [displayLightGradeUniforms] settles for [transfer].
 */
internal fun uploadGradeUniforms(
  gl: WebGl2,
  program: JsAny,
  transfer: HdrTransfer,
) {
  val uniforms = transfer.displayLightGradeUniforms()
  gl.uniform1f(gl.getUniformLocation(program, "uWhite"), uniforms.white)
  gl.uniform1f(gl.getUniformLocation(program, "uDisplayGamma"), uniforms.displayGamma)
  gl.uniform1f(gl.getUniformLocation(program, "uOotfGamma"), uniforms.ootfGamma)
  gl.uniform1f(gl.getUniformLocation(program, "uCeiling"), uniforms.ceiling)
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

/**
 * GLSL for `vec3 hlgScene(vec3 signal)`, which decodes an HLG signal per channel to scene light normalised against
 * [HLG_NOMINAL_PEAK_NITS], the way `sceneFromHlgSignal` does.
 */
internal val HLG_SCENE_GLSL =
  """
  vec3 hlgScene(vec3 signal) {
    vec3 low = signal * signal / 3.0;
    vec3 high = (exp((signal - ${HLG_C.glsl()}) / ${HLG_A.glsl()}) + ${HLG_B.glsl()}) / 12.0;
    return mix(low, high, step(0.5, signal));
  }
  """.trimIndent()

private val HLG_EOTF_GLSL =
  """
  $HLG_SCENE_GLSL
  vec3 eotf(vec3 signal) {
    return hlgDisplayFromScene(hlgScene(signal));
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
