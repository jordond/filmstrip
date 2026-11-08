package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import kotlin.math.ceil
import kotlin.time.Duration
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
  private val speed = speedFor(decodedUs, lengthUs.toDouble())

  override fun getSpeed(timeUs: Long): Float = speed

  override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/**
 * Plays each stretch of an item at the speed that lands its start on the time [frames] asks for, so every sample
 * sitting on a stretch's start is written exactly there.
 *
 * Stretch `i` runs from `frames.inputsUs[i]` up to the next one, or to [ShownFrames.decodedUs] for the last, and lands
 * on `frames.outputsUs[i]`. The last runs to [ShownFrames.lengthUs]. media3 reads every stretch twice, once rounding
 * each one and once flooring their sum, and each speed is the fastest float that plays the sum so far for at least
 * the time asked of it. Both readings then land on every stretch's start, and on the length unless the last stretch
 * holds for more than about four seconds, where no float may land and it runs long by at most [speedStepUs]. That is
 * media3's limit, since it only takes a float.
 *
 * @property frames The stretches, read off the samples the item decodes.
 */
internal class SlotPace(
  val frames: ShownFrames,
  override val clipSpeed: Double,
) : ClipPace {
  private val starts = frames.inputsUs.toLongArray()
  private val speeds = FloatArray(starts.size)

  init {
    var played = 0.0
    starts.indices.forEach { index ->
      val input = (starts.getOrNull(index + 1) ?: frames.decodedUs) - starts[index]
      val output = frames.outputsUs.getOrNull(index + 1) ?: frames.lengthUs
      speeds[index] = speedFor(input, output - played)
      // The same sum, in the same order, as media3's floored reading of the length.
      played += input / speeds[index].toDouble()
    }
  }

  /**
   * Each frame the item writes, as how far into the item it is written paired with where it sits in the item.
   */
  val shown: List<Pair<Duration, Duration>>
    get() = frames.shown.map { (written, position) -> written.microseconds to position.microseconds }

  override fun getSpeed(timeUs: Long): Float = speeds[stretchAt(timeUs)]

  override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = starts.getOrNull(stretchAt(timeUs) + 1) ?: C.TIME_UNSET

  private fun stretchAt(timeUs: Long): Int {
    val found = starts.binarySearch(timeUs)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
  }
}

/**
 * The fastest float speed that plays [input] microseconds for at least [output] of them.
 *
 * media3 divides by the speed as a float, so this walks a float at a time to it. That speed plays for as little past
 * [output] as media3 allows, which is under half a microsecond for an [output] under about four seconds.
 *
 * Requires [input] and [output] to be positive.
 */
private fun speedFor(
  input: Long,
  output: Double,
): Float {
  require(input > 0 && output > 0) { "input ($input) and output ($output) must be positive" }
  var speed = (input / output).toFloat()
  while (input / speed.toDouble() < output) speed = Math.nextDown(speed)
  // Clamps to media3's float: where no speed lands both readings, this stops on the one that plays long by a step.
  while (input / Math.nextUp(speed).toDouble() >= output) speed = Math.nextUp(speed)
  return speed
}

/**
 * The most a stretch of [outputUs] can play past it, which is one step between two neighbouring float speeds.
 *
 * A float moves by at most one part in 2^23 of itself from one value to the next, and the played length moves with it.
 * Under 2^22 microseconds this is under half a microsecond, which is why every shorter stretch lands exactly.
 */
internal fun speedStepUs(outputUs: Long): Long = ceil(outputUs * FLOAT_STEP / (1 - FLOAT_STEP)).toLong()

private val FLOAT_STEP = Math.ulp(1f).toDouble()
