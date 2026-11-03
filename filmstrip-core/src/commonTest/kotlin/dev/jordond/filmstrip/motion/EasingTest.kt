package dev.jordond.filmstrip.motion

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The curves themselves, since every paced effect reads its position out of one.
 *
 * Every curve agrees at both ends, so what these assert is the middle.
 */
class EasingTest {
  @Test
  fun everyCurveStartsAtZeroAndEndsAtOne() {
    Easing.entries.forEach { easing ->
      withClue("$easing at the start") { easing.paced(0f) shouldBe (0f plusOrMinus TOLERANCE) }
      withClue("$easing at the end") { easing.paced(1f) shouldBe (1f plusOrMinus TOLERANCE) }
    }
  }

  @Test
  fun everyCurveIsClampedToItsEnds() {
    Easing.entries.forEach { easing ->
      withClue("$easing before the start") { easing.paced(-2f) shouldBe (0f plusOrMinus TOLERANCE) }
      withClue("$easing after the end") { easing.paced(3f) shouldBe (1f plusOrMinus TOLERANCE) }
    }
  }

  @Test
  fun theCurvesPartCompanyInTheMiddle() {
    Easing.Linear.paced(0.4f) shouldBe (0.4f plusOrMinus TOLERANCE)
    // 0.4 squared.
    Easing.EaseIn.paced(0.4f) shouldBe (0.16f plusOrMinus TOLERANCE)
    // 0.4 * (2 - 0.4).
    Easing.EaseOut.paced(0.4f) shouldBe (0.64f plusOrMinus TOLERANCE)
    // 2 * 0.4 squared.
    Easing.EaseInOut.paced(0.4f) shouldBe (0.32f plusOrMinus TOLERANCE)
    // (4 - 1.2) * 0.6 - 1.
    Easing.EaseInOut.paced(0.6f) shouldBe (0.68f plusOrMinus TOLERANCE)
  }

  @Test
  fun easeInOutIsSymmetricAboutItsMiddle() {
    listOf(0.1f, 0.25f, 0.4f, 0.49f).forEach { fraction ->
      withClue("at $fraction") {
        Easing.EaseInOut.paced(1f - fraction) shouldBe (1f - Easing.EaseInOut.paced(fraction) plusOrMinus TOLERANCE)
      }
    }
    Easing.EaseInOut.paced(0.5f) shouldBe (0.5f plusOrMinus TOLERANCE)
  }

  @Test
  fun everyCurveRisesFromEndToEnd() {
    Easing.entries.forEach { easing ->
      var previous = easing.paced(0f)
      (1..STEPS).forEach { step ->
        val next = easing.paced(step.toFloat() / STEPS)
        withClue("$easing fell back at step $step") { (next >= previous - TOLERANCE).shouldBeTrue() }
        previous = next
      }
    }
  }

  private companion object {
    const val TOLERANCE = 1e-4f
    const val STEPS = 100
  }
}
