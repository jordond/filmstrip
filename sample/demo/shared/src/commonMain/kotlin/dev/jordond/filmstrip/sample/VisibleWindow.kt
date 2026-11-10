package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.edit.TimeRange
import kotlin.time.Duration.Companion.seconds

internal fun visibleWindow(
  enabled: Boolean,
  startSeconds: Float,
  endSeconds: Float,
): TimeRange? {
  if (!enabled) return null
  val start = startSeconds.toDouble().seconds
  val end = endSeconds.toDouble().seconds
  return if (end > start) TimeRange(start, end) else null
}
