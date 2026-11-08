package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.color.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.color.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.color.sdrSignalCeiling
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

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

      uniforms.white shouldBe 1f
      uniforms.displayGamma shouldBe SDR_DISPLAY_GAMMA.toFloat()
      uniforms.ootfGamma shouldBe 1f
      uniforms.ceiling shouldBe transfer.sdrSignalCeiling
    }
  }

  // The ceiling is where the format runs out rather than where the pipeline normalises, so it stays
  // per transfer however the light reaches the shader.
  @Test
  fun `the ceiling still parts the two transfers`() {
    val pq = HdrTransfer.Pq.hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)
    val hlg = HdrTransfer.Hlg.hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)

    hlg.ceiling shouldNotBe pq.ceiling
    withClue("PQ carries the higher peak, so it has the higher ceiling") { (pq.ceiling > hlg.ceiling) shouldBe true }
  }

  @Test
  fun `a backend holding scene light runs the opto-optical transfer`() {
    val uniforms = HdrTransfer.Hlg.hdrColorMatrixUniforms(white = 0.203f, holdsSceneLight = true)

    uniforms.white shouldBe 0.203f
    uniforms.ootfGamma shouldBe HLG_SYSTEM_GAMMA.toFloat()
  }

  // Both callers paste this into a shader of their own, so it has to be a function block and
  // nothing else.
  @Test
  fun `the shared body is one function with no entry point of its own`() {
    HDR_COLOR_MATRIX_GLSL.contains("vec3 filmstripGradeHdr(") shouldBe true
    HDR_COLOR_MATRIX_GLSL.contains("void main") shouldBe false
    HDR_COLOR_MATRIX_GLSL.contains("uniform") shouldBe false
  }
}
