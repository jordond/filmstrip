package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.sdrSignalCeiling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The figures both backends read a kept grade through, and the body they both compile.
 *
 * A backend holding display light normalised against reference white passes one for white, which is
 * what leaves the two transfer functions reading the frame the same way. Only the ceiling still
 * parts them there, because that is the format's own limit rather than the pipeline's.
 */
class HdrColorMatrixShaderTest {
  @Test
  fun `a backend holding display light at reference white reads both transfers the same way`() {
    HdrTransfer.entries.forEach { transfer ->
      val uniforms = transfer.hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)

      assertEquals(1f, uniforms.white)
      assertEquals(SDR_DISPLAY_GAMMA.toFloat(), uniforms.displayGamma)
      assertEquals(1f, uniforms.ootfGamma)
      assertEquals(transfer.sdrSignalCeiling, uniforms.ceiling)
    }
  }

  // The ceiling is where the format runs out rather than where the pipeline normalises, so it stays
  // per transfer however the light reaches the shader.
  @Test
  fun `the ceiling still parts the two transfers`() {
    val pq = HdrTransfer.Pq.hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)
    val hlg = HdrTransfer.Hlg.hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)

    assertNotEquals(pq.ceiling, hlg.ceiling)
    assertTrue(pq.ceiling > hlg.ceiling, "PQ carries the higher peak, so it has the higher ceiling")
  }

  @Test
  fun `a backend holding scene light runs the opto-optical transfer`() {
    val uniforms = HdrTransfer.Hlg.hdrColorMatrixUniforms(white = 0.203f, holdsSceneLight = true)

    assertEquals(0.203f, uniforms.white)
    assertEquals(HLG_SYSTEM_GAMMA.toFloat(), uniforms.ootfGamma)
  }

  // Both callers paste this into a shader of their own, so it has to be a function block and
  // nothing else.
  @Test
  fun `the shared body is one function with no entry point of its own`() {
    assertTrue(HDR_COLOR_MATRIX_GLSL.contains("vec3 filmstripGradeHdr("))
    assertFalse(HDR_COLOR_MATRIX_GLSL.contains("void main"))
    assertFalse(HDR_COLOR_MATRIX_GLSL.contains("uniform"))
  }
}
