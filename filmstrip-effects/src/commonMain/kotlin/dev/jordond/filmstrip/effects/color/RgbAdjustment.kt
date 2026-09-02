package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multiply each colour channel by its own factor.
 *
 * [Brightness] with a separate factor per channel: the multiply lands on the encoded signal, black
 * stays black, and a channel that climbs past white clamps there. Unequal factors tint the frame,
 * so scaling red down and blue up cools it.
 *
 * @property red The red multiplier, where `1f` leaves it unchanged. Negative values are read as
 * `0f`, and a NaN as `1f`.
 * @property green The green multiplier, read the same way.
 * @property blue The blue multiplier, read the same way.
 */
@Serializable
@SerialName(EffectIds.RGB_ADJUSTMENT)
@Poko
public class RgbAdjustment(
  public val red: Float = 1f,
  public val green: Float = 1f,
  public val blue: Float = 1f,
) : EffectSpec {
  override val id: String get() = EffectIds.RGB_ADJUSTMENT

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Multiply each colour channel by its own factor, where `1f` leaves a channel unchanged.
 */
public fun EffectsBuilder.rgbAdjustment(
  red: Float = 1f,
  green: Float = 1f,
  blue: Float = 1f,
): EffectsBuilder = add(RgbAdjustment(red, green, blue))

/**
 * The matrix behind the adjustment: each channel's factor on the diagonal, read the way
 * [Brightness.scale] reads its one factor.
 */
internal val RgbAdjustment.matrix: ColorMatrix
  get() = scaleMatrix(red.asChannelScale(), green.asChannelScale(), blue.asChannelScale())

private fun Float.asChannelScale(): Float = if (isNaN()) 1f else coerceAtLeast(0f)
