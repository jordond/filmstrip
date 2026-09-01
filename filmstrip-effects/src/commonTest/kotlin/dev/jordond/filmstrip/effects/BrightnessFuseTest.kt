package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The fold that decides what stacked brightnesses mean, run once in shared code so a backend that
 * clamps around every effect lands on the same frame as one that clamps at the encoder.
 */
@OptIn(InternalFilmstripApi::class)
class BrightnessFuseTest {
  @Test
  fun stackedFactorsMultiply() {
    val fused = listOf(Brightness(4f), Brightness(0.25f)).fusedBrightness()

    assertEquals(1, fused.size)
    assertEquals(1f, assertIs<Brightness>(fused.single()).factor)
  }

  @Test
  fun aRunOfMoreThanTwoMultiplies() {
    val fused = listOf(Brightness(2f), Brightness(2f), Brightness(0.5f)).fusedBrightness()

    assertEquals(2f, assertIs<Brightness>(fused.single()).factor)
  }

  @Test
  fun theFactorIsNormalisedBeforeItIsMultiplied() {
    // Multiplying the authored factors would carry the NaN through and turn the negative into a
    // darkening. Both are read as the no-op and as black first.
    val fromNaN = listOf(Brightness(Float.NaN), Brightness(0.5f)).fusedBrightness()
    val fromNegative = listOf(Brightness(-2f), Brightness(0.5f)).fusedBrightness()

    assertEquals(0.5f, assertIs<Brightness>(fromNaN.single()).factor)
    assertEquals(0f, assertIs<Brightness>(fromNegative.single()).factor)
  }

  @Test
  fun anEffectBetweenThemBreaksTheRun() {
    val chain: List<EffectSpec> = listOf(Brightness(4f), Crop(AspectRatio.Portrait), Brightness(0.25f))

    assertEquals(chain, chain.fusedBrightness())
  }

  @Test
  fun aChainWithOneBrightnessIsLeftAlone() {
    val chain: List<EffectSpec> = listOf(Brightness(4f), Crop(AspectRatio.Portrait))

    assertSame(chain, chain.fusedBrightness())
  }
}
