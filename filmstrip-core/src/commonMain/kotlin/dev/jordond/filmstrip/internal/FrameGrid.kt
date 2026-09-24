package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The output slot [time] lands on at [frameRate].
 *
 * Slots run from zero at the start of the composition, one every `1 / frameRate` seconds. A time between two slots
 * takes the nearer one, and a time exactly half way takes the later one. Whole microsecond arithmetic, so a time on a
 * whole second lands on that second's own slot at every rate. A time before the composition lands on its first slot,
 * and one too far out to count in microseconds, an infinite one included, lands on the last slot that can be counted.
 *
 * @throws IllegalArgumentException when [frameRate] is zero or negative.
 */
@InternalFilmstripApi
public fun outputSlotOf(
  time: Duration,
  frameRate: Int,
): Long {
  requirePositive(frameRate)
  val micros = time.inWholeMicroseconds.coerceIn(0, (Long.MAX_VALUE - MICROS_PER_SECOND) / (2L * frameRate))
  return (micros * frameRate * 2 + MICROS_PER_SECOND) / (2 * MICROS_PER_SECOND)
}

/**
 * The output slots [span] covers at [frameRate].
 *
 * Runs from the slot the span's start lands on up to, and not including, the slot its end lands on, so the span covers
 * one slot for each point half way between two slots that it reaches. Two spans laid end to end cover ranges that
 * meet, and a span that reaches no half way point covers none. The span must carry an end.
 *
 * @throws IllegalArgumentException when [frameRate] is zero or negative.
 */
@InternalFilmstripApi
public fun outputSlotsOf(
  span: TimeRange,
  frameRate: Int,
): LongRange {
  requirePositive(frameRate)
  return outputSlotOf(span.start, frameRate) until outputSlotOf(checkNotNull(span.endExclusive), frameRate)
}

/**
 * Where [slot] sits on the composition timeline at [frameRate].
 *
 * Whole microseconds, rounded down, so a slot's time never lands later than a frame written on it.
 *
 * @throws IllegalArgumentException when [frameRate] is zero or negative.
 */
@InternalFilmstripApi
public fun outputSlotTime(
  slot: Long,
  frameRate: Int,
): Duration {
  requirePositive(frameRate)
  return (slot * MICROS_PER_SECOND / frameRate).microseconds
}

private fun requirePositive(frameRate: Int) =
  require(frameRate > 0) { "The output frame grid needs a positive frame rate, and $frameRate fps is not one." }

private const val MICROS_PER_SECOND = 1_000_000L
