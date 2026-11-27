package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.edit.TimeRange

/**
 * The time range an overlay shows for, from [startSeconds] to [endSeconds], or null when it shows for the whole edit.
 *
 * An overlay that is not [enabled] for timing shows throughout, and so does one whose end is not after its start.
 */
public fun visibleWindow(
  enabled: Boolean,
  startSeconds: Float,
  endSeconds: Float,
): TimeRange? {
  if (!enabled) return null
  val start = startSeconds.asSeconds()
  val end = endSeconds.asSeconds()
  return if (end > start) TimeRange(start, end) else null
}
