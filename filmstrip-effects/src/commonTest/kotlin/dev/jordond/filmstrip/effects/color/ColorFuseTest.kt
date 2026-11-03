package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.AspectRatio
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * The fold that decides what a run of color effects means, run once in shared code so a backend
 * that clamps around every effect lands on the same frame as one that clamps at the encoder.
 */
class ColorFuseTest {
  @Test
  fun stackedBrightnessFactorsMultiply() {
    val fused = listOf(Brightness(4f), Brightness(0.25f)).fusedColorMatrices()

    fused.size shouldBe 1
    fused
      .single()
      .spec
      .shouldBeInstanceOf<Brightness>()
      .factor shouldBe 1f
  }

  @Test
  fun aRunOfMoreThanTwoMultiplies() {
    val fused = listOf(Brightness(2f), Brightness(2f), Brightness(0.5f)).fusedColorMatrices()

    fused
      .single()
      .spec
      .shouldBeInstanceOf<Brightness>()
      .factor shouldBe 2f
  }

  @Test
  fun theFactorIsNormalisedBeforeItIsMultiplied() {
    // Multiplying the authored factors would carry the NaN through and turn the negative into a
    // darkening. Both are read as the no-op and as black first.
    val fromNaN = listOf(Brightness(Float.NaN), Brightness(0.5f)).fusedColorMatrices()
    val fromNegative = listOf(Brightness(-2f), Brightness(0.5f)).fusedColorMatrices()

    fromNaN
      .single()
      .spec
      .shouldBeInstanceOf<Brightness>()
      .factor shouldBe 0.5f
    fromNegative
      .single()
      .spec
      .shouldBeInstanceOf<Brightness>()
      .factor shouldBe 0f
  }

  // Brightness then saturation is the run where clamping in between shows: a channel pushed past
  // white by the multiply still feeds the luma the desaturation mixes back in.
  @Test
  fun aMixedRunFoldsToOneMatrixInChainOrder() {
    val brightness = Brightness(2f)
    val saturation = Saturation(0.5f)

    val fused = listOf(brightness, saturation).fusedColorMatrices()

    val matrix = fused.single().spec.shouldBeInstanceOf<ColorMatrix>()
    matrix shouldBe brightness.matrix.then(saturation.matrix)
    val pixel = matrix.transform(0.6f, 0.2f, 0.2f)
    val clampedBetween = saturation.matrix.transform(1f, 0.4f, 0.4f)
    pixel[0] shouldBe (0.885f plusOrMinus TOLERANCE)
    pixel[1] shouldBe (0.485f plusOrMinus TOLERANCE)
    clampedBetween[0] shouldBe (0.764f plusOrMinus TOLERANCE)
  }

  @Test
  fun aMatrixAmongBrightnessesFoldsTheWholeRun() {
    val chain = listOf(Brightness(2f), Contrast(0.5f), Brightness(0.5f))

    val fused = chain.fusedColorMatrices()

    val expected = Brightness(2f).matrix.then(Contrast(0.5f).matrix).then(Brightness(0.5f).matrix)
    fused.single().spec.shouldBeInstanceOf<ColorMatrix>() shouldBe expected
  }

  // A refusal or a degradation is reported against the names the caller wrote, so the fold keeps
  // them beside the effect it made.
  @Test
  fun aFoldedRunNamesTheEffectsItStandsFor() {
    val chain = listOf(Brightness(2f), Contrast(0.5f), Brightness(0.5f))

    val fused = chain.fusedColorMatrices()

    fused.single().sources shouldBe chain
  }

  @Test
  fun anEffectBetweenThemBreaksTheRun() {
    val chain: List<EffectSpec> = listOf(Brightness(4f), Crop(AspectRatio.Portrait), Contrast(2f))

    assertEachLeftAsItself(chain, chain.fusedColorMatrices())
  }

  @Test
  fun aLoneEffectIsLeftAsItself() {
    val chain: List<EffectSpec> = listOf(Contrast(2f), Crop(AspectRatio.Portrait))

    assertEachLeftAsItself(chain, chain.fusedColorMatrices())
  }

  // A run of one beside a run that folds goes through the same flush as the fold, and comes out as
  // the authored effect rather than as a matrix of one.
  @Test
  fun aLoneEffectBesideAFoldedRunIsLeftAsItself() {
    val trailing = Brightness(0.5f)
    val chain: List<EffectSpec> = listOf(Brightness(2f), Contrast(2f), Crop(AspectRatio.Portrait), trailing)

    val fused = chain.fusedColorMatrices()

    fused.size shouldBe 3
    fused[0].spec.shouldBeInstanceOf<ColorMatrix>()
    fused[0].sources shouldBe chain.take(2)
    fused[1].spec shouldBeSameInstanceAs chain[2]
    fused[2].spec shouldBeSameInstanceAs trailing
    fused[2].sources shouldBe listOf(trailing)
  }

  private fun assertEachLeftAsItself(
    chain: List<EffectSpec>,
    fused: List<FoldedSpec>,
  ) {
    fused.size shouldBe chain.size
    chain.zip(fused).forEach { (spec, folded) ->
      folded.spec shouldBeSameInstanceAs spec
      folded.sources shouldBe listOf(spec)
    }
  }

  private companion object {
    const val TOLERANCE = 0.002f
  }
}
