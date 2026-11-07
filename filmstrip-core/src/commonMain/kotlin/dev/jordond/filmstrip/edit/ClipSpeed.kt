package dev.jordond.filmstrip.edit

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

/**
 * The speeds a clip can play at, from 32 times slower to 32 times faster.
 *
 * This is AVFoundation's limit on a scaled time range and its pitch algorithm. The planner refuses a speed outside it
 * by name, so no backend ever clamps one.
 */
public val CLIP_SPEED_RANGE: ClosedFloatingPointRange<Double> = (1.0 / 32.0)..32.0

/**
 * Tests a speed against [CLIP_SPEED_RANGE].
 *
 * @return True for a finite speed inside the range. Zero, a negative, NaN and infinity all answer false.
 */
@InternalFilmstripApi
public fun isValidClipSpeed(speed: Double): Boolean = speed.isFinite() && speed in CLIP_SPEED_RANGE

/**
 * How long [sourceLength] of source runs on the timeline when played at [speed].
 *
 * The answer is rounded to the nearest nanosecond, and a speed of 1 hands [sourceLength] back as it is.
 */
@InternalFilmstripApi
public fun outputLengthOf(
  sourceLength: Duration,
  speed: Double,
): Duration =
  if (speed == 1.0) {
    sourceLength
  } else {
    (sourceLength.toDouble(DurationUnit.NANOSECONDS) / speed).nanoseconds
  }

/**
 * How much source plays through [outputLength] of timeline at [speed].
 *
 * The answer is rounded to the nearest nanosecond, and a speed of 1 hands [outputLength] back as it is.
 */
@InternalFilmstripApi
public fun sourceLengthOf(
  outputLength: Duration,
  speed: Double,
): Duration =
  if (speed == 1.0) {
    outputLength
  } else {
    (outputLength.toDouble(DurationUnit.NANOSECONDS) * speed).nanoseconds
  }

/**
 * How far into a clip's trimmed source the frame [outputOffset] into its slot on the timeline comes from.
 *
 * Both offsets are counted from the clip's own opening, so the caller adds where the trim starts.
 */
@InternalFilmstripApi
public fun sourceOffsetAt(
  outputOffset: Duration,
  speed: Double,
): Duration = sourceLengthOf(outputOffset, speed)

/**
 * How many audio frames at [sampleRate] a stretch of [length] holds, to the nearest frame.
 *
 * Every backend pins a clip's audio at a speed to this count rather than to what its stretcher works out.
 */
@InternalFilmstripApi
public fun audioFramesOf(
  length: Duration,
  sampleRate: Int,
): Long = audioFramesOf(length.toDouble(DurationUnit.SECONDS), sampleRate)

/**
 * How many audio frames at [sampleRate] a stretch of [seconds] holds, to the nearest frame.
 *
 * A caller already counting in seconds reads this one, which keeps its figure from passing through a whole nanosecond.
 */
@InternalFilmstripApi
public fun audioFramesOf(
  seconds: Double,
  sampleRate: Int,
): Long = (seconds * sampleRate).roundToLong()
