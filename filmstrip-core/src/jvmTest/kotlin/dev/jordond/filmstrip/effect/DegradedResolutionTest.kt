package dev.jordond.filmstrip.effect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a degraded resolution carries, which is every cause in the order it was given and never none.
 */
class DegradedResolutionTest {
  @Test
  fun `a degraded resolution with no cause is rejected`() {
    assertFailsWith<IllegalArgumentException> { EffectResolution.Degraded(EFFECT, emptyList()) }
  }

  @Test
  fun `a degraded resolution keeps every cause in order`() {
    val causes =
      listOf(
        Degradation(DegradationReason.ColorSpaceConverted, "converted"),
        Degradation(DegradationReason.PrecisionReduced, "cut"),
      )

    assertEquals(causes, EffectResolution.Degraded(EFFECT, causes).causes)
  }

  @Test
  fun `a degraded resolution keeps its causes when the list passed in changes`() {
    val first = Degradation(DegradationReason.ColorSpaceConverted, "converted")
    val second = Degradation(DegradationReason.PrecisionReduced, "cut")
    val third = Degradation(DegradationReason.ResolutionClamped, "clamped")
    val causes = mutableListOf(first, second, third)

    val degraded = EffectResolution.Degraded(EFFECT, causes)
    causes.clear()

    assertEquals(listOf(first, second, third), degraded.causes)
  }

  @Test
  fun `two degraded resolutions with the same effect and causes are equal`() {
    val causes = listOf(Degradation(DegradationReason.ColorSpaceConverted, "converted"))

    assertEquals(EffectResolution.Degraded(EFFECT, causes), EffectResolution.Degraded(EFFECT, causes.toList()))
    assertEquals(
      EffectResolution.Degraded(EFFECT, causes).hashCode(),
      EffectResolution.Degraded(EFFECT, causes.toList()).hashCode(),
    )
  }

  private companion object {
    val EFFECT = PlatformEffect(FilterFragment())
  }
}
