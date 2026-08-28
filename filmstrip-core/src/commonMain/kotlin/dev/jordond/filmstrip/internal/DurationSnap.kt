package dev.jordond.filmstrip.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rounds down to the nearest multiple of [grid], or returns the value unchanged when [grid] is not
 * positive.
 *
 * Position listeners snap to a grid so a collector only wakes when the value it displays changes.
 */
internal fun Duration.snapTo(grid: Duration): Duration {
  val gridMillis = grid.inWholeMilliseconds
  if (gridMillis <= 0L) return this
  val millis = inWholeMilliseconds
  return (millis - millis.mod(gridMillis)).milliseconds
}
