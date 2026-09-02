package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multiply every colour channel of the frame.
 *
 * The multiply lands on the encoded signal rather than on linear light, which is what
 * `filter: brightness()` in a browser does and what every backend here reaches for first. Black
 * stays black and the result is clamped to the encoding's range, so a factor above `1f` brightens
 * until a channel saturates.
 *
 * On an export that keeps an HDR grade the same factor is read as the display light ratio an SDR
 * display would have produced, so a frame looks the same graded or not. HDR has headroom above
 * white where SDR clips, so a factor above `1f` matches an SDR export through the midtones and
 * keeps going where that one saturates.
 *
 * A run of colour effects multiplies out before anything clamps, so a factor above `1f` followed by
 * its reciprocal returns the frame unchanged rather than a saturated frame the second factor darkens.
 *
 * @property factor The multiplier, where `1f` leaves the frame unchanged and `0f` writes black.
 * Negative values are read as `0f`, and anything that is not a finite number as `1f`.
 */
@Serializable
@SerialName(EffectIds.BRIGHTNESS)
@Poko
public class Brightness(
  public val factor: Float,
) : EffectSpec {
  override val id: String get() = EffectIds.BRIGHTNESS

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Multiply every colour channel by [factor], where `1f` leaves the frame unchanged.
 */
public fun EffectsBuilder.brightness(factor: Float): EffectsBuilder = add(Brightness(factor))

/**
 * The multiplier a backend actually applies, computed here so all four apply the same number.
 *
 * Finiteness is checked before the floor because `coerceAtLeast` passes a NaN straight through and
 * leaves an infinity where it was. Either one reaches a backend as a shader uniform, a filter
 * argument and a matrix element, which fail three different ways, so both become the no-op here.
 */
internal val Brightness.scale: Float get() = if (factor.isFinite()) factor.coerceAtLeast(0f) else 1f

/**
 * The matrix behind a brightness: the scale on every channel and nothing else.
 */
internal val Brightness.matrix: ColorMatrix get() = scaleMatrix(scale, scale, scale)
