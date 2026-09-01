package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.NormalizedRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Crop to an explicit rectangle.
 *
 * Rotation runs before crop, so [rect] is normalised to the rotated frame rather than to the source
 * frame.
 *
 * @property rect The region to keep, as fractions of the frame entering the crop.
 */
@Serializable
@SerialName(EffectIds.CROP_RECT)
@Poko
public class CropRect(
  public val rect: NormalizedRect,
) : EffectSpec {
  override val id: String get() = EffectIds.CROP_RECT

  override val stage: EffectStage get() = EffectStage.Geometry
}

/**
 * Crop to an explicit [rect], expressed in the frame rotation produced.
 */
public fun EffectsBuilder.crop(rect: NormalizedRect): EffectsBuilder = add(CropRect(rect))
