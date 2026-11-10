package dev.jordond.filmstrip.effects.spatial

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage

/**
 * Blur the frame with a Gaussian kernel.
 *
 * [sigma] is a fraction of the width of the frame the blur runs on, so the same blur looks the same at any export size
 * and in a smaller preview. That frame is the clip's own at clip scope and the composed frame at composition scope.
 * Pixels past its edge read as the nearest edge pixel, so the edges do not darken.
 *
 * The blur runs on the encoded codes, the same domain a color effect runs in, and on a kept HDR grade it runs on the
 * signal the grade's light encodes to, so an HDR export keeps its grade.
 *
 * @property sigma The kernel's standard deviation as a fraction of the frame width, from `0f` to `0.1f`. At `0f` the
 * frame is left as it was.
 * @throws IllegalArgumentException when [sigma] is outside `0f..0.1f`.
 */
@Poko
public class GaussianBlur(
  public val sigma: Float,
) : EffectSpec {
  init {
    require(sigma in 0f..MAX_SIGMA) { "A blur's sigma runs from 0 to $MAX_SIGMA of the frame width, not $sigma." }
  }

  override val id: String get() = EffectIds.GAUSSIAN_BLUR

  override val stage: EffectStage get() = EffectStage.Spatial

  override val isInert: Boolean get() = sigma == 0f

  private companion object {
    const val MAX_SIGMA = 0.1f
  }
}

/**
 * Blur the frame by [sigma], a fraction of the frame width from `0f` to `0.1f`.
 */
public fun EffectsBuilder.blur(sigma: Float): EffectsBuilder = add(GaussianBlur(sigma))
