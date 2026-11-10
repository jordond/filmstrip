package dev.jordond.filmstrip.color

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VignetteShapeTest {
  @Test
  fun `the centre keeps its codes`() {
    gainAt(0.5, 0.5) shouldBe (1.0 plusOrMinus 1e-12)
  }

  @Test
  fun `a corner sits at a distance of one`() {
    // t is 0.7 over 0.9 along the ramp from 0.3 to 1.2, and smoothstep of that is 637 over 729.
    gainAt(0.0, 0.0) shouldBe ((1.0 - 0.5 * 637.0 / 729.0) plusOrMinus 1e-12)
    gainAt(1.0, 1.0) shouldBe (gainAt(0.0, 0.0) plusOrMinus 1e-12)
  }

  @Test
  fun `the radius is half way down the ramp`() {
    // On the diagonal, 0.375 of the way from the centre to a corner along each axis is a distance of 0.75.
    gainAt(0.125, 0.125) shouldBe (0.75 plusOrMinus 1e-12)
  }

  @Test
  fun `a quarter of the way along the ramp follows the smoothstep`() {
    // A distance of 0.525 is a quarter of the way from 0.3 to 1.2, where smoothstep is 0.15625.
    val along = 0.525 / 0.75 * 0.375
    gainAt(0.5 - along, 0.5 - along) shouldBe ((1.0 - 0.5 * 0.15625) plusOrMinus 1e-12)
  }

  @Test
  fun `a wide frame is round on screen`() {
    val aspect = 1920.0 / 1080.0
    val across = vignetteGain(0.5 + 500.0 / 1920.0, 0.5, aspect, STRENGTH, RADIUS, SOFTNESS)
    val down = vignetteGain(0.5, 0.5 + 500.0 / 1080.0, aspect, STRENGTH, RADIUS, SOFTNESS)

    across shouldBeLessThan 1.0
    across shouldBe (down plusOrMinus 1e-12)
  }

  @Test
  fun `a strength of zero leaves every pixel as it was`() {
    for (point in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
      vignetteGain(point, 1.0 - point, 1.0, 0.0, RADIUS, SOFTNESS) shouldBe 1.0
    }
  }

  @Test
  fun `a softness of zero is a hard edge at the radius`() {
    vignetteGain(0.13, 0.13, 1.0, STRENGTH, RADIUS, 0.0) shouldBe 1.0
    vignetteGain(0.12, 0.12, 1.0, STRENGTH, RADIUS, 0.0) shouldBe ((1.0 - STRENGTH) plusOrMinus 1e-12)
  }

  private fun gainAt(
    x: Double,
    y: Double,
  ): Double = vignetteGain(x, y, 1.0, STRENGTH, RADIUS, SOFTNESS)

  private companion object {
    const val STRENGTH = 0.5
    const val RADIUS = 0.75
    const val SOFTNESS = 0.45
  }
}
