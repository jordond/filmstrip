package dev.jordond.filmstrip.color

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test

class BlurPlanTest {
  @Test
  fun `a plan keeps its sigma and taps inside the limits at mid-range widths`() {
    for (sigma in SIGMAS) {
      for (width in WIDTHS) {
        val plan = BlurPlan.of(sigma, width)

        withClue("sigma $sigma at $width px") {
          plan.sigmaPx shouldBeLessThanOrEqualTo BlurPlan.MAX_SIGMA_PX
          plan.taps shouldBeLessThanOrEqualTo BlurPlan.MAX_TAPS
          plan.weights.size shouldBe plan.taps + 1
          plan.taps shouldBe ceil(BlurPlan.SUPPORT_SIGMAS * plan.sigmaPx).toInt()
        }
      }
    }
  }

  @Test
  fun `a plan lands on the requested sigma as a fraction of the frame`() {
    for (sigma in SIGMAS) {
      for (width in WIDTHS) {
        val plan = BlurPlan.of(sigma, width)

        val variance = 2.0 * plan.weights.indices.sumOf { it * it * plan.weights[it] }
        val effective = sqrt(variance) / plan.width

        // Cutting a Gaussian off at 3 sigma takes about 1.4 percent off its second moment's root.
        withClue("sigma $sigma at $width px") { effective shouldBe (sigma plusOrMinus sigma * 0.02) }
      }
    }
  }

  @Test
  fun `the weights sum to one with each side counted`() {
    val weights = BlurPlan.of(0.05, 1920).weights

    (weights[0] + 2.0 * weights.drop(1).sum()) shouldBe (1.0 plusOrMinus 1e-12)
  }

  @Test
  fun `a plan scales the blurred frame by its factor`() {
    val plan = BlurPlan.of(0.05, 1920)

    plan.factor shouldBe 12
    plan.width shouldBe 160
    plan.heightOf(1080) shouldBe 90
  }

  @Test
  fun `a sigma of zero has no plan`() {
    shouldThrow<IllegalArgumentException> { BlurPlan.of(0.0, 1920) }
  }

  private companion object {
    val SIGMAS = listOf(0.012, 0.03, 0.05, 0.075)
    val WIDTHS = listOf(640, 1280, 1920, 2560, 3840)
  }
}
