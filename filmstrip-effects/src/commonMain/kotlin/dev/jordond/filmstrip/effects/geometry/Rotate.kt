package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rotate the frame, counter-clockwise.
 *
 * The rotation is baked into the pixels rather than written as container metadata. Source
 * orientation is corrected automatically, so this is deliberate rotation on top of that.
 *
 * @property degrees Degrees counter-clockwise. A multiple of 90 turns the frame exactly. Any other angle turns the
 * picture about its centre and grows the frame to the bounding box [turnedFrame] answers, with black in the corners
 * the picture leaves, and the browser refuses one.
 */
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

/**
 * The frame this rotation leaves out of a frame of [size].
 *
 * A quarter turn swaps the two sides and a half turn keeps them. Any other angle grows the frame to the bounding box
 * of the turned picture, each side rounded to the nearest pixel and then down to an even one, so a 1920x1080 frame
 * turned 30 degrees lands on 2202x1894. Every backend draws onto this frame and the planner sizes the output from it,
 * so they agree on the frame a rotation hands on.
 */
@InternalFilmstripApi
public fun Rotate.turnedFrame(size: Size): Size {
  if (degrees % HALF_TURN_DEGREES == 0) return size
  if (degrees % QUARTER_TURN_DEGREES == 0) return Size(size.height, size.width)
  val radians = degrees * PI / HALF_TURN_DEGREES
  val cos = abs(cos(radians))
  val sin = abs(sin(radians))
  return Size(
    evenSide(size.width * cos + size.height * sin),
    evenSide(size.width * sin + size.height * cos),
  )
}

private fun evenSide(side: Double): Int {
  val rounded = side.roundToInt()
  return (rounded - rounded % EVEN).coerceAtLeast(EVEN)
}

private const val HALF_TURN_DEGREES = 180
private const val QUARTER_TURN_DEGREES = 90
private const val EVEN = 2
