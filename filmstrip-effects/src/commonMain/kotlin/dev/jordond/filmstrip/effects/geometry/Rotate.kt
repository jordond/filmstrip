package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rotate the frame, counter-clockwise.
 *
 * The rotation is baked into the pixels rather than written as container metadata. Source
 * orientation is corrected automatically, so this is deliberate rotation on top of that.
 *
 * @property degrees Degrees counter-clockwise: 0, 90, 180 or 270.
 */
@Serializable
@SerialName(EffectIds.ROTATE)
@Poko
public class Rotate(
  public val degrees: Int,
) : EffectSpec {
  override val id: String get() = EffectIds.ROTATE

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Rotate counter-clockwise by [degrees], baked into the pixels.
 */
public fun EffectsBuilder.rotate(degrees: Int): EffectsBuilder = add(Rotate(degrees))
