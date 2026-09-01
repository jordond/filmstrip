package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.FlipAxis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirror the frame across an axis.
 *
 * @property axis The axis to mirror across.
 */
@Serializable
@SerialName(EffectIds.FLIP)
@Poko
public class Flip(
  public val axis: FlipAxis,
) : EffectSpec {
  override val id: String get() = EffectIds.FLIP

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Mirror across [axis].
 */
public fun EffectsBuilder.flip(axis: FlipAxis): EffectsBuilder = add(Flip(axis))
