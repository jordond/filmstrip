package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Fit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Set the output height in pixels. The width follows from the composition's aspect.
 *
 * @property targetHeight Target height in pixels. Rounded up to the encoder's alignment, and the
 * rounding is reported.
 * @property fit How the frame is fitted to [targetHeight].
 */
@Serializable
@SerialName(EffectIds.SCALE)
@Poko
public class Scale(
  public val targetHeight: Int,
  public val fit: Fit = Fit.Contain,
) : EffectSpec {
  override val id: String get() = EffectIds.SCALE

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Scale the output to [targetHeight] pixels tall.
 */
public fun EffectsBuilder.scale(
  targetHeight: Int,
  fit: Fit = Fit.Contain,
): EffectsBuilder = add(Scale(targetHeight, fit))
