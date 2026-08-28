package dev.jordond.filmstrip.effects

import dev.drewhamilton.poko.Poko
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
 * @property factor The multiplier, where `1f` leaves the frame unchanged and `0f` writes black.
 * Negative values are read as `0f`, and a NaN as `1f`.
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
 * The multiplier a backend actually applies, computed here so all four apply the same number.
 *
 * NaN is checked before the floor because it compares false against everything, so `coerceAtLeast`
 * passes it straight through. It reaches a backend as a shader uniform, a filter argument and a
 * matrix element, which fail three different ways, so it is turned into the no-op here instead.
 */
internal val Brightness.scale: Float get() = if (factor.isNaN()) 1f else factor.coerceAtLeast(0f)
