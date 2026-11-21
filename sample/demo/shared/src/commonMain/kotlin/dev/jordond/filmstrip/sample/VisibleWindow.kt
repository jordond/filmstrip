package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.edit.TimeRange

internal fun visibleWindow(
  enabled: Boolean,
  startSeconds: Float,
  endSeconds: Float,
): TimeRange? {
  if (!enabled) return null
  val start = startSeconds.asSeconds()
  val end = endSeconds.asSeconds()
  return if (end > start) TimeRange(start, end) else null
}
