package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Move every pixel towards or away from its own grey.
 *
 * A pixel's grey is its Rec. 709 luma, and [factor] is how far the pixel sits from it: `0f` lands on
 * the grey and turns the frame monochrome, `1f` leaves it alone, and `2f` pushes each channel twice
 * as far from the grey as it started. The luma itself is unchanged at any factor, so a grey pixel
 * stays exactly where it was.
 *
 * @property factor How saturated the frame comes out, where `1f` leaves it unchanged and `0f` is
 * grey. Negative values are read as `0f`, and anything that is not a finite number as `1f`.
 */
@Serializable
@SerialName(EffectIds.SATURATION)
@Poko
public class Saturation(
  public val factor: Float,
) : EffectSpec {
  override val id: String get() = EffectIds.SATURATION

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Scale how far every pixel sits from its own grey by [factor], where `1f` leaves the frame
 * unchanged.
 */
public fun EffectsBuilder.saturation(factor: Float): EffectsBuilder = add(Saturation(factor))

/**
 * Turn the frame monochrome, keeping each pixel's Rec. 709 luma.
 */
public fun EffectsBuilder.grayscale(): EffectsBuilder = add(Saturation(0f))

/**
 * The amount a backend actually applies, computed here so all four apply the same number.
 */
internal val Saturation.amount: Float get() = if (factor.isFinite()) factor.coerceAtLeast(0f) else 1f

/**
 * The matrix behind a saturation: each channel is the luma plus its own distance from the luma
 * scaled by the amount.
 */
internal val Saturation.matrix: ColorMatrix
  get() {
    val grey = 1f - amount
    return ColorMatrix(
      rr = grey * LUMA_RED + amount,
      rg = grey * LUMA_GREEN,
      rb = grey * LUMA_BLUE,
      gr = grey * LUMA_RED,
      gg = grey * LUMA_GREEN + amount,
      gb = grey * LUMA_BLUE,
      br = grey * LUMA_RED,
      bg = grey * LUMA_GREEN,
      bb = grey * LUMA_BLUE + amount,
    )
  }

/**
 * The weight red carries in Rec. 709 luma.
 */
internal const val LUMA_RED: Float = 0.2126f

/**
 * The weight green carries in Rec. 709 luma.
 */
internal const val LUMA_GREEN: Float = 0.7152f

/**
 * The weight blue carries in Rec. 709 luma.
 */
internal const val LUMA_BLUE: Float = 0.0722f
