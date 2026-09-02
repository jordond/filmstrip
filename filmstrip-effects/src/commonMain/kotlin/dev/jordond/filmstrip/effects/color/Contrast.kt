package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stretch or flatten every colour channel about mid grey.
 *
 * Each channel moves away from the middle of the encoded range by [factor], so `2f` doubles the
 * distance from mid grey and `0.5f` halves it. The pivot is the encoded signal's midpoint, which is
 * what `filter: contrast()` in a browser pivots on, and a channel that stretches past black or white
 * clamps there.
 *
 * On an export that keeps an HDR grade the pivot is the light that midpoint makes on an SDR display
 * at reference white, about 44 cd/m2, and a channel stretches up to the format's peak rather than
 * to white.
 *
 * @property factor The gain about mid grey, where `1f` leaves the frame unchanged and `0f` paints
 * the whole frame mid grey. Negative values are read as `0f`, and anything that is not a finite
 * number as `1f`.
 */
@Serializable
@SerialName(EffectIds.CONTRAST)
@Poko
public class Contrast(
  public val factor: Float,
) : EffectSpec {
  override val id: String get() = EffectIds.CONTRAST

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Stretch every colour channel about mid grey by [factor], where `1f` leaves the frame unchanged.
 */
public fun EffectsBuilder.contrast(factor: Float): EffectsBuilder = add(Contrast(factor))

/**
 * The gain a backend actually applies, computed here so all four apply the same number.
 */
internal val Contrast.gain: Float get() = if (factor.isFinite()) factor.coerceAtLeast(0f) else 1f

/**
 * The matrix behind a contrast: each channel scaled by the gain, with the bias that keeps mid grey
 * where it was.
 */
internal val Contrast.matrix: ColorMatrix
  get() {
    val bias = MID_GREY * (1f - gain)
    return ColorMatrix(rr = gain, rBias = bias, gg = gain, gBias = bias, bb = gain, bBias = bias)
  }

private const val MID_GREY = 0.5f
