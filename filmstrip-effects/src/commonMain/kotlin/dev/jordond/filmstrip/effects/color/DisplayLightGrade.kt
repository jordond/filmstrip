package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BT2020_LUMA_B
import dev.jordond.filmstrip.color.BT2020_LUMA_G
import dev.jordond.filmstrip.color.BT2020_LUMA_R
import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.color.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * The figures a backend holding linear display light, with reference white at one, grades light of this transfer
 * through.
 *
 * PQ is graded on the display light itself. HLG is graded on scene light normalised against [HLG_NOMINAL_PEAK_NITS],
 * which [displayLightGradeGlsl] turns the display light into and back, so the grade meets the per channel reading the
 * backends holding scene light grade HLG with.
 */
@InternalFilmstripApi
public fun HdrTransfer.displayLightGradeUniforms(): HdrColorMatrixUniforms =
  when (this) {
    HdrTransfer.Pq -> {
      hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)
    }
    HdrTransfer.Hlg -> {
      hdrColorMatrixUniforms(white = HDR_REFERENCE_WHITE_NITS / HLG_NOMINAL_PEAK_NITS, holdsSceneLight = true)
    }
  }

/**
 * GLSL for `vec3 toGradeLight(vec3 light)` and `vec3 fromGradeLight(vec3 graded)`, which carry linear display light
 * with reference white at one into the light [displayLightGradeUniforms] describes and back.
 */
@InternalFilmstripApi
public fun HdrTransfer.displayLightGradeGlsl(): String =
  when (this) {
    HdrTransfer.Pq -> IDENTITY_GRADE_LIGHT_GLSL
    HdrTransfer.Hlg -> "$HLG_OOTF_GLSL\n$HLG_GRADE_LIGHT_GLSL"
  }

/**
 * GLSL for HLG's opto-optical transfer, driven by the color's luminance.
 *
 * `vec3 hlgSceneFromDisplay(vec3 nits)` is `hlgSceneFromNits` spelled in GLSL, and `vec3 hlgDisplayFromScene(vec3
 * scene)` is its inverse. Scene light is normalised against [HLG_NOMINAL_PEAK_NITS]. A black pixel is answered
 * outright, since GLSL leaves the power of zero to the driver.
 */
@InternalFilmstripApi
public val HLG_OOTF_GLSL: String =
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

/**
 * GLSL for `vec3 toGradeLight(vec3 light)` and `vec3 fromGradeLight(vec3 graded)` that leave the light as it is, for a
 * frame already in the light its grade reads.
 */
@InternalFilmstripApi
public const val IDENTITY_GRADE_LIGHT_GLSL: String =
  """
  vec3 toGradeLight(vec3 light) { return light; }
  vec3 fromGradeLight(vec3 graded) { return graded; }
  """

/**
 * This figure as a GLSL float literal.
 *
 * A whole number renders without a decimal point on the js target, where a Kotlin Float is a JavaScript number, and
 * GLSL will not multiply a vector by an integer literal. Spelling the point back on keeps one shader source across
 * every target.
 */
@InternalFilmstripApi
public fun Number.glsl(): String {
  val text = toString()

  return if (text.any { it == '.' || it == 'e' || it == 'E' }) text else "$text.0"
}

private val HLG_GRADE_LIGHT_GLSL =
  """
  vec3 toGradeLight(vec3 light) {
    return hlgSceneFromDisplay(light * ${HDR_REFERENCE_WHITE_NITS.glsl()});
  }
  vec3 fromGradeLight(vec3 graded) {
    return hlgDisplayFromScene(graded) / ${HDR_REFERENCE_WHITE_NITS.glsl()};
  }
  """.trimIndent()
