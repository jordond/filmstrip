package dev.jordond.filmstrip.effects.color

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turn every hue around the colour wheel by the same angle.
 *
 * The rotation is the linear one `filter: hue-rotate()` in a browser applies, which spins the
 * chroma about the luma axis and leaves a grey pixel exactly where it was. A full turn is the
 * identity, so `degrees` is read modulo 360.
 *
 * @property degrees How far to turn, where `0f` leaves the frame unchanged and `180f` swaps every
 * hue for the one opposite it. Anything that is not a finite number is read as `0f`.
 */
@Serializable
@SerialName(EffectIds.HUE_ROTATE)
@Poko
public class HueRotate(
  public val degrees: Float,
) : EffectSpec {
  override val id: String get() = EffectIds.HUE_ROTATE

  override val stage: EffectStage get() = EffectStage.Color
}

/**
 * Turn every hue by [degrees], where `0f` leaves the frame unchanged.
 */
public fun EffectsBuilder.hueRotate(degrees: Float): EffectsBuilder = add(HueRotate(degrees))

/**
 * The angle a backend actually applies, in degrees.
 *
 * Guarded here rather than on the matrix, because the trig runs first and both a NaN and an infinity
 * come out of it as a NaN in all nine entries.
 */
internal val HueRotate.angle: Float get() = if (degrees.isFinite()) degrees else 0f

/**
 * The matrix behind a hue rotation, from the Filter Effects specification's `hueRotate` table.
 */
internal val HueRotate.matrix: ColorMatrix
  get() {
    val radians = angle * PI / HALF_TURN
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    return ColorMatrix(
      rr = 0.213f + c * 0.787f - s * 0.213f,
      rg = 0.715f - c * 0.715f - s * 0.715f,
      rb = 0.072f - c * 0.072f + s * 0.928f,
      gr = 0.213f - c * 0.213f + s * 0.143f,
      gg = 0.715f + c * 0.285f + s * 0.140f,
      gb = 0.072f - c * 0.072f - s * 0.283f,
      br = 0.213f - c * 0.213f - s * 0.787f,
      bg = 0.715f - c * 0.715f + s * 0.715f,
      bb = 0.072f + c * 0.928f + s * 0.072f,
    )
  }

private const val HALF_TURN = 180.0
