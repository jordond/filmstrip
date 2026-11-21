package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.effects.filters.internal.FilterStep
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The chain [BloomShader] runs for a [Bloom].
 */
class BloomShaderTest {
  @Test
  fun `the response blurs across then up and the last step lays it over the input`() {
    val steps = BloomShader.steps(Bloom())

    steps.map { it.name } shouldBe listOf("response", "blurAcross", "blurUpward", "bloom")
    steps
      .first()
      .reads.values
      .single() shouldBe FilterStep.INPUT
    steps
      .last()
      .reads.values
      .toList() shouldBe listOf(FilterStep.INPUT, "blurUpward")
  }

  @Test
  fun `the blur steps run at the plan's size and the last at the frame's`() {
    for (spread in listOf(0f, 0.5f, 1f)) {
      val steps = BloomShader.steps(Bloom(spread = spread))
      val plan = BloomSignal.blurPlan(spread.toDouble(), WIDTH)
      val blurred = Size(plan.width, plan.heightOf(HEIGHT))

      steps.dropLast(1).forEach { it.targetAt(WIDTH, HEIGHT) shouldBe blurred }
      steps.last().targetAt(WIDTH, HEIGHT) shouldBe Size(WIDTH, HEIGHT)
      steps
        .first()
        .uniformsAt(0.0, WIDTH, HEIGHT)
        .getValue("uBox")
        .single() shouldBe plan.factor.toFloat()
    }
  }

  @Test
  fun `the uniforms carry the params of the controls`() {
    val bloom = Bloom(intensity = 0.5f, threshold = 0.6f, spread = 0.25f)
    val params = BloomParams.of(bloom)
    val steps = BloomShader.steps(bloom)

    val response = steps.first().uniformsAt(0.0, WIDTH, HEIGHT)
    response.getValue("uThreshold").single() shouldBe (params.threshold.toFloat() plusOrMinus FLOAT_STEP)
    response.getValue("uKneeEnd").single() shouldBe (params.kneeEnd.toFloat() plusOrMinus FLOAT_STEP)
    steps
      .last()
      .uniformsAt(0.0, WIDTH, HEIGHT)
      .getValue("uGain")
      .single() shouldBe (params.gain.toFloat() plusOrMinus FLOAT_STEP)
  }

  @Test
  fun `the fragments open on their version`() {
    BloomShader.steps(Bloom()).forEach { it.fragment.startsWith("#version 300 es") shouldBe true }
  }

  private companion object {
    // A uniform is held in 32 bits, which a browser reads back as a double a step off the figure.
    const val FLOAT_STEP = 1e-6f

    const val WIDTH = 1366
    const val HEIGHT = 768
  }
}
