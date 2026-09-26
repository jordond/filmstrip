package dev.jordond.filmstrip.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SrgbTransferTest {
  @Test
  fun `a mid code decodes to the light the sRGB curve puts there`() {
    // A pure 2.2 power would land at 0.2176 and a linear reading at 0.5, so only the curve itself
    // reads this figure.
    srgbToLinear(0.5f) shouldBeNear 0.214041f
    srgbToLinear(0.25f) shouldBeNear 0.050876f
    srgbToLinear(0.75f) shouldBeNear 0.522522f
  }

  @Test
  fun `mid grey light encodes to the code the sRGB curve puts there`() {
    linearToSrgb(0.18f) shouldBeNear 0.461356f
    linearToSrgb(0.5f) shouldBeNear 0.735357f
  }

  @Test
  fun `the linear segment below the knee is a straight scale`() {
    srgbToLinear(0.02f) shouldBeNear 0.02f / 12.92f
    linearToSrgb(0.001f) shouldBeNear 0.001f * 12.92f
  }

  @Test
  fun `the ends of the range are still the ends`() {
    srgbToLinear(0f) shouldBeNear 0f
    srgbToLinear(1f) shouldBeNear 1f
    linearToSrgb(0f) shouldBeNear 0f
    linearToSrgb(1f) shouldBeNear 1f
  }

  @Test
  fun `a value outside the range is clamped to it`() {
    srgbToLinear(-0.5f) shouldBeNear 0f
    srgbToLinear(1.5f) shouldBeNear 1f
    linearToSrgb(-0.5f) shouldBeNear 0f
    linearToSrgb(1.5f) shouldBeNear 1f
  }

  @Test
  fun `a channel decoded and encoded again lands where it started`() {
    listOf(0.01f, 0.04045f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f).forEach { encoded ->
      linearToSrgb(srgbToLinear(encoded)) shouldBeNear encoded
    }
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    assertTrue(abs(this - expected) < TOLERANCE, "expected $expected but was $this")
  }

  private companion object {
    const val TOLERANCE = 1e-5f
  }
}
