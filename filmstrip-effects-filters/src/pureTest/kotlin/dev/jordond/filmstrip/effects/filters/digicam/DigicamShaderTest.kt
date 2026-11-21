package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effects.filters.internal.FilterStep
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The chain [DigicamShader] runs for a [Digicam].
 */
class DigicamShaderTest {
  @Test
  fun `the frame blurs across then up and the last step reads both`() {
    val steps = DigicamShader.steps(Digicam())

    steps.map { it.name } shouldBe listOf("response", "blurAcross", "blurUpward", "digicam")
    steps
      .last()
      .reads.values
      .toList() shouldBe listOf(FilterStep.INPUT, "blurUpward")
  }

  @Test
  fun `every step runs at the frame's own size`() {
    for ((width, height) in listOf(640 to 360, 1920 to 1080, 3840 to 2160)) {
      val steps = DigicamShader.steps(Digicam())

      steps.forEach { it.targetAt(width, height) shouldBe Size(width, height) }
      steps
        .first()
        .uniformsAt(0.0, width, height)
        .getValue("uBox")
        .single() shouldBe 1f
    }
  }

  @Test
  fun `the uniforms carry the params of the controls and the frame's tick and cell`() {
    val digicam = Digicam(sharpness = 0.5f, noise = 0.25f, clip = 0.75f, seed = 77)
    val params = DigicamParams.of(digicam)
    val uniforms = DigicamShader.steps(digicam).last().uniformsAt(SECONDS, WIDTH, HEIGHT)

    uniforms.getValue("uHaloGain").single() shouldBe (params.haloGain.toFloat() plusOrMinus FLOAT_STEP)
    uniforms.getValue("uClipGain").single() shouldBe (params.clipGain.toFloat() plusOrMinus FLOAT_STEP)
    uniforms.getValue("uNoise").single() shouldBe (params.noiseAmplitude.toFloat() plusOrMinus FLOAT_STEP)
    uniforms.getValue("uSeed").single() shouldBe 77f
    uniforms.getValue("uTick").single() shouldBe DigicamSignal.tickAt(SECONDS).toFloat()
    uniforms.getValue("uCellPx").single() shouldBe DigicamSignal.cellPx(HEIGHT).toFloat()
    uniforms.getValue("uResolution").toList() shouldBe listOf(WIDTH.toFloat(), HEIGHT.toFloat())
  }

  @Test
  fun `the last step sets every uniform its fragment declares but the flip`() {
    val last = DigicamShader.steps(Digicam()).last()
    val declared =
      Regex("uniform (?:float|vec2) ([^;]+);")
        .findAll(last.fragment)
        .flatMap { it.groupValues[1].split(",") }
        .map { it.trim() }
        .toSet()

    last.uniformsAt(SECONDS, WIDTH, HEIGHT).keys shouldBe declared - DigicamShader.FLIP_V
  }

  @Test
  fun `the fragments open on their version`() {
    DigicamShader.steps(Digicam()).forEach { it.fragment.startsWith("#version 300 es") shouldBe true }
  }

  private companion object {
    // A uniform is held in 32 bits, which a browser reads back as a double a step off the figure.
    const val FLOAT_STEP = 1e-6f

    const val SECONDS = 12.4
    const val WIDTH = 1366
    const val HEIGHT = 768
  }
}
