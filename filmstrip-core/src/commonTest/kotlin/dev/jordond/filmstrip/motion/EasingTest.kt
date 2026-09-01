package dev.jordond.filmstrip.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The curves themselves, since every paced effect reads its position out of one.
 *
 * Every curve agrees at both ends, so what these assert is the middle.
 */
class EasingTest {
  @Test
  fun everyCurveStartsAtZeroAndEndsAtOne() {
    Easing.entries.forEach { easing ->
      assertEquals(0f, easing.paced(0f), TOLERANCE, "$easing at the start")
      assertEquals(1f, easing.paced(1f), TOLERANCE, "$easing at the end")
    }
  }

  @Test
  fun everyCurveIsClampedToItsEnds() {
    Easing.entries.forEach { easing ->
      assertEquals(0f, easing.paced(-2f), TOLERANCE, "$easing before the start")
      assertEquals(1f, easing.paced(3f), TOLERANCE, "$easing after the end")
    }
  }

  @Test
  fun theCurvesPartCompanyInTheMiddle() {
    assertEquals(0.4f, Easing.Linear.paced(0.4f), TOLERANCE)
    // 0.4 squared.
    assertEquals(0.16f, Easing.EaseIn.paced(0.4f), TOLERANCE)
    // 0.4 * (2 - 0.4).
    assertEquals(0.64f, Easing.EaseOut.paced(0.4f), TOLERANCE)
    // 2 * 0.4 squared.
    assertEquals(0.32f, Easing.EaseInOut.paced(0.4f), TOLERANCE)
    // (4 - 1.2) * 0.6 - 1.
    assertEquals(0.68f, Easing.EaseInOut.paced(0.6f), TOLERANCE)
  }

  @Test
  fun easeInOutIsSymmetricAboutItsMiddle() {
    listOf(0.1f, 0.25f, 0.4f, 0.49f).forEach { fraction ->
      assertEquals(
        1f - Easing.EaseInOut.paced(fraction),
        Easing.EaseInOut.paced(1f - fraction),
        TOLERANCE,
        "at $fraction",
      )
    }
    assertEquals(0.5f, Easing.EaseInOut.paced(0.5f), TOLERANCE)
  }

  @Test
  fun everyCurveRisesFromEndToEnd() {
    Easing.entries.forEach { easing ->
      var previous = easing.paced(0f)
      (1..STEPS).forEach { step ->
        val next = easing.paced(step.toFloat() / STEPS)
        assertTrue(next >= previous - TOLERANCE, "$easing fell back at step $step")
        previous = next
      }
    }
  }

  private companion object {
    const val TOLERANCE = 1e-4f
    const val STEPS = 100
  }
}
