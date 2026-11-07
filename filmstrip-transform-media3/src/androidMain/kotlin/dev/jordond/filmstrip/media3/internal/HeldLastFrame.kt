package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import dev.jordond.filmstrip.edit.outputLengthOf
import kotlin.math.ceil
import kotlin.time.Duration.Companion.microseconds

/**
 * How an item's speed provider runs it, which a frame reader needs to find a frame the way the player would.
 *
 * @property clipSpeed The speed the clip was written at.
 */
internal sealed interface ClipPace : SpeedProvider {
  val clipSpeed: Double
}

/**
 * Plays an item of [decodedUs] for exactly [lengthUs], which is the length [clipSpeed] asks of it.
 *
 * The speed is the one [speedFor] lands on, since media3 rounds and floors its own readings of the length.
 *
 * @param decodedUs How long the item decodes for, in microseconds.
 * @param lengthUs How long the item has to run for, in microseconds.
 */
internal class ConstantClipSpeed(
  override val clipSpeed: Double,
  decodedUs: Long,
  lengthUs: Long,
) : ClipPace {
  private val speed = speedFor(decodedUs, lengthUs)

  override fun getSpeed(timeUs: Long): Float = speed

  override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/**
 * Runs an item at [clipSpeed] up to its last frame and slower after it, so that frame is held until the item has run
 * for the length its slots ask for.
 *
 * Only what comes after [fromUs] is slowed, so every frame keeps its own time at [clipSpeed], the last one included.
 * media3 reads the held length twice, once rounding and once flooring, and the speed is the one both readings land
 * exactly on the length for. A hold of more than a few seconds can run past the length by up to [speedStepUs], which is
 * as close as media3's float speed gets.
 *
 * @property fromUs Where the last frame sits, in microseconds from the start of the item.
 * @param decodedUs How long the item decodes for, in microseconds.
 * @param lengthUs How long the item has to run for, in microseconds.
 */
internal class HeldLastFrame(
  val fromUs: Long,
  decodedUs: Long,
  lengthUs: Long,
  override val clipSpeed: Double = 1.0,
) : ClipPace {
  private val playsUs = outputLengthOf(fromUs.microseconds, clipSpeed).inWholeMicroseconds
  private val playing = if (clipSpeed == 1.0 || fromUs == 0L) 1f else speedFor(fromUs, playsUs)
  private val held: Float

  init {
    val input = decodedUs - fromUs
    val output = lengthUs - playsUs
    require(input > 0 && input / clipSpeed < output) {
      "A hold has to slow a frame down, and $input microseconds at $clipSpeed already fills $output of them."
    }
    held = speedFor(input, output)
  }

  override fun getSpeed(timeUs: Long): Float = if (timeUs < fromUs) playing else held

  override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = if (timeUs < fromUs) fromUs else C.TIME_UNSET
}

/**
 * The speed that plays [input] microseconds for exactly [output] of them, or for as little past it as media3 allows.
 *
 * media3 divides by the speed as a float, rounds the answer in one place and floors it in another, so both readings
 * land on [output] only when the played length sits within half a microsecond past it. This walks a float at a time to
 * the fastest speed that plays for at least [output], which is the one that lands whenever any float does.
 *
 * Past about four seconds of [output], one float step moves the played length by more than half a microsecond and no
 * float may land. That is media3's limit, since it only takes a float, and the frame is then held long rather than
 * short, by at most [speedStepUs].
 *
 * Requires [input] and [output] to be positive.
 */
private fun speedFor(
  input: Long,
  output: Long,
): Float {
  require(input > 0 && output > 0) { "input ($input) and output ($output) must be positive" }
  var speed = (input.toDouble() / output).toFloat()
  while (input / speed.toDouble() < output) speed = Math.nextDown(speed)
  // Clamps to media3's float: where no speed lands both readings, this stops on the one that plays long by a step.
  while (input / Math.nextUp(speed).toDouble() >= output) speed = Math.nextUp(speed)
  return speed
}

/**
 * The most a hold of [outputUs] can play past it, which is one step between two neighbouring float speeds.
 *
 * A float moves by at most one part in 2^23 of itself from one value to the next, and the played length moves with it.
 * Under 2^22 microseconds this is under half a microsecond, which is why every shorter hold lands exactly.
 */
internal fun speedStepUs(outputUs: Long): Long = ceil(outputUs * FLOAT_STEP / (1 - FLOAT_STEP)).toLong()

private val FLOAT_STEP = Math.ulp(1f).toDouble()
