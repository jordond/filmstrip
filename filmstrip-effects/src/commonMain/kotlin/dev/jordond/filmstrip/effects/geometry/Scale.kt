package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Fit

/**
 * Set the output height in pixels. The width follows from the composition's aspect.
 *
 * @property heightPx Target height in pixels. Rounded down to the encoder's alignment, and the
 * rounding is reported as a `ResolutionClamped` adjustment.
 * @property fit How the frame is fitted to [heightPx].
 */
@Poko
public class Scale(
  public val heightPx: Int,
  public val fit: Fit = Fit.Contain,
) : EffectSpec {
  override val id: EffectId get() = EffectIds.SCALE

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Scale the output to [heightPx] pixels tall.
 *
 * @param heightPx Target height in pixels.
 * @param fit How the frame is fitted to [heightPx].
 */
public fun EffectsBuilder.scale(
  heightPx: Int,
  fit: Fit = Fit.Contain,
): EffectsBuilder = add(Scale(heightPx, fit))
