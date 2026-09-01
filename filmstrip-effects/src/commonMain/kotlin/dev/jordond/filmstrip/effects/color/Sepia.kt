package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tone the frame towards the warm brown of an old photograph.
 *
 * The tone is the one `filter: sepia()` in a browser applies, blended with the untouched frame by
 * [amount].
 *
 * @property amount How much of the tone to apply, where `0f` leaves the frame unchanged and `1f`
 * is fully toned. Values outside that range are clamped to it, and a NaN is read as `0f`.
 */
@Serializable
@SerialName(EffectIds.SEPIA)
@Poko
public class Sepia(
  public val amount: Float = 1f,
) : EffectSpec {
  override val id: String get() = EffectIds.SEPIA

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Tone the frame sepia by [amount], where `1f` is fully toned.
 */
public fun EffectsBuilder.sepia(amount: Float = 1f): EffectsBuilder = add(Sepia(amount))

/**
 * The blend a backend actually applies, computed here so all four apply the same number.
 */
internal val Sepia.mix: Float get() = if (amount.isNaN()) 0f else amount.coerceIn(0f, 1f)

/**
 * The matrix behind a sepia tone: the identity blended towards the specification's `sepia` table.
 */
internal val Sepia.matrix: ColorMatrix
  get() {
    val keep = 1f - mix
    return ColorMatrix(
      rr = keep + mix * 0.393f,
      rg = mix * 0.769f,
      rb = mix * 0.189f,
      gr = mix * 0.349f,
      gg = keep + mix * 0.686f,
      gb = mix * 0.168f,
      br = mix * 0.272f,
      bg = mix * 0.534f,
      bb = keep + mix * 0.131f,
    )
  }
