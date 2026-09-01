package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Turn every colour into its negative.
 *
 * Each channel is reflected about mid grey, so black and white trade places and every hue becomes
 * the one opposite it. A partial [amount] moves each channel that fraction of the way towards its
 * reflection, which is what `filter: invert()` in a browser does.
 *
 * @property amount How far to invert, where `0f` leaves the frame unchanged and `1f` is the full
 * negative. Values outside that range are clamped to it, and a NaN is read as `0f`.
 */
@Serializable
@SerialName(EffectIds.INVERT)
@Poko
public class Invert(
  public val amount: Float = 1f,
) : EffectSpec {
  override val id: String get() = EffectIds.INVERT

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Invert every colour by [amount], where `1f` is the full negative.
 */
public fun EffectsBuilder.invert(amount: Float = 1f): EffectsBuilder = add(Invert(amount))

/**
 * The blend a backend actually applies, computed here so all four apply the same number.
 */
internal val Invert.mix: Float get() = if (amount.isNaN()) 0f else amount.coerceIn(0f, 1f)

/**
 * The matrix behind an inversion: each channel scaled by `1 - 2 * amount` and lifted by `amount`,
 * which is the identity at zero and `1 - v` at one.
 */
internal val Invert.matrix: ColorMatrix
  get() {
    val slope = 1f - 2f * mix
    return ColorMatrix(rr = slope, rBias = mix, gg = slope, gBias = mix, bb = slope, bBias = mix)
  }
