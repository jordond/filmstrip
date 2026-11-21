package dev.jordond.filmstrip.sample

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads a slider's seconds as a duration, to the millisecond.
 *
 * A float holds 0.4 as a little over 0.4, and a duration built straight from it carries the extra nanoseconds into
 * the edit. Rounding to the millisecond gives the time the control shows.
 */
internal fun Float.asSeconds(): Duration = (this * MILLIS_PER_SECOND).roundToLong().milliseconds

private const val MILLIS_PER_SECOND = 1000f
