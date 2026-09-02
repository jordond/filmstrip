package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.AspectRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The fold that decides what a run of colour effects means, run once in shared code so a backend
 * that clamps around every effect lands on the same frame as one that clamps at the encoder.
 */
@OptIn(InternalFilmstripApi::class)
class ColorFuseTest {
  @Test
  fun stackedBrightnessFactorsMultiply() {
    val fused = listOf(Brightness(4f), Brightness(0.25f)).fusedColorMatrices()

    assertEquals(1, fused.size)
    assertEquals(1f, assertIs<Brightness>(fused.single().spec).factor)
  }

  @Test
  fun aRunOfMoreThanTwoMultiplies() {
    val fused = listOf(Brightness(2f), Brightness(2f), Brightness(0.5f)).fusedColorMatrices()

    assertEquals(2f, assertIs<Brightness>(fused.single().spec).factor)
  }

  @Test
  fun theFactorIsNormalisedBeforeItIsMultiplied() {
    // Multiplying the authored factors would carry the NaN through and turn the negative into a
    // darkening. Both are read as the no-op and as black first.
    val fromNaN = listOf(Brightness(Float.NaN), Brightness(0.5f)).fusedColorMatrices()
    val fromNegative = listOf(Brightness(-2f), Brightness(0.5f)).fusedColorMatrices()

    assertEquals(0.5f, assertIs<Brightness>(fromNaN.single().spec).factor)
    assertEquals(0f, assertIs<Brightness>(fromNegative.single().spec).factor)
  }

  // Brightness then saturation is the run where clamping in between shows: a channel pushed past
  // white by the multiply still feeds the luma the desaturation mixes back in.
  @Test
  fun aMixedRunFoldsToOneMatrixInChainOrder() {
    val brightness = Brightness(2f)
    val saturation = Saturation(0.5f)

    val fused = listOf(brightness, saturation).fusedColorMatrices()

    val matrix = assertIs<ColorMatrix>(fused.single().spec)
    assertEquals(brightness.matrix.then(saturation.matrix), matrix)
    val pixel = matrix.transform(0.6f, 0.2f, 0.2f)
    val clampedBetween = saturation.matrix.transform(1f, 0.4f, 0.4f)
    assertEquals(0.885f, pixel[0], TOLERANCE)
    assertEquals(0.485f, pixel[1], TOLERANCE)
    assertEquals(0.764f, clampedBetween[0], TOLERANCE)
  }

  @Test
  fun aMatrixAmongBrightnessesFoldsTheWholeRun() {
    val chain = listOf(Brightness(2f), Contrast(0.5f), Brightness(0.5f))

    val fused = chain.fusedColorMatrices()

    val expected = Brightness(2f).matrix.then(Contrast(0.5f).matrix).then(Brightness(0.5f).matrix)
    assertEquals(expected, assertIs<ColorMatrix>(fused.single().spec))
  }

  // A refusal or a degradation is reported against the names the caller wrote, so the fold keeps
  // them beside the effect it made.
  @Test
  fun aFoldedRunNamesTheEffectsItStandsFor() {
    val chain = listOf(Brightness(2f), Contrast(0.5f), Brightness(0.5f))

    val fused = chain.fusedColorMatrices()

    assertEquals(chain, fused.single().sources)
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

    assertEquals(3, fused.size)
    assertIs<ColorMatrix>(fused[0].spec)
    assertEquals(chain.take(2), fused[0].sources)
    assertSame(chain[2], fused[1].spec)
    assertSame(trailing, fused[2].spec)
    assertEquals(listOf(trailing), fused[2].sources)
  }

  private fun assertEachLeftAsItself(
    chain: List<EffectSpec>,
    fused: List<FoldedSpec>,
  ) {
    assertEquals(chain.size, fused.size)
    chain.zip(fused).forEach { (spec, folded) ->
      assertSame(spec, folded.spec)
      assertEquals(listOf(spec), folded.sources)
    }
  }

  private companion object {
    const val TOLERANCE = 0.002f
  }
}
