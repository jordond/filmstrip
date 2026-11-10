package dev.jordond.filmstrip.effects.filters.camcorder

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

// One state of a camcorder display and the composition time it starts at.
internal class OsdChange(
  val at: Duration,
  val state: OsdState,
)

// Every state the display shows from [start] until [end], each with the time it starts at, the first at [start]. A
// display only changes on a blink edge, a second of the clock or of a counter, or a counted frame, so those are the
// only times read, each moved onto the state clock. Unless [withFrames] is set, every frame count is hidden and its
// frames are not read.
internal fun CamcorderOsd.changesWithin(
  start: Duration,
  end: Duration,
  withFrames: Boolean = true,
): List<OsdChange> {
  val shown = { at: Duration -> if (withFrames) stateAt(at) else framesHidden(spec, stateAt(at)) }
  val from = start.inWholeMicroseconds.coerceAtLeast(0)
  val until = end.inWholeMicroseconds
  val candidates = mutableListOf<Long>()
  var half = ceilTo(from, BLINK_HALF_MICROS)
  while (half < until) {
    candidates += half
    half += BLINK_HALF_MICROS
  }
  val frameStarts = frameStartsWithinSecond(rate)
  spec.elements.filterIsInstance<OsdElement.Counter>().forEach { counter ->
    val offset = counter.start.inWholeMicroseconds
    val within = if (counter.withFrames && withFrames) listOf(0L) + frameStarts else listOf(0L)
    var second = (from + offset) / MICROS_PER_SECOND * MICROS_PER_SECOND
    while (second - offset < until) {
      within.forEach { frame ->
        val at = second + frame - offset
        candidates += at
      }
      second += MICROS_PER_SECOND
    }
  }
  val changes = mutableListOf(OsdChange(from.microseconds, shown(from.microseconds)))
  candidates
    .map { ceilTo(it, OsdParams.STATE_CLOCK_MICROS) }
    .filter { it > from && it < until }
    .sorted()
    .distinct()
    .forEach { at ->
      val state = shown(at.microseconds)
      if (changes.last().state != state) changes += OsdChange(at.microseconds, state)
    }
  return changes
}

// The first microsecond of each frame after the first within a second, counted at [frameRate] the way the counter does.
internal fun frameStartsWithinSecond(frameRate: Double): List<Long> {
  val last = ceil(frameRate).toLong() - 1
  return (1..last).map { frame ->
    var at = ceil((frame - FRAME_MARGIN) / frameRate * MICROS_PER_SECOND).toLong().coerceIn(0, MICROS_PER_SECOND - 1)
    while (at > 0 && frameWithinSecond(at - 1, frameRate) >= frame) at--
    while (at < MICROS_PER_SECOND - 1 && frameWithinSecond(at, frameRate) < frame) at++
    at
  }
}

private fun ceilTo(
  micros: Long,
  step: Long,
): Long = (micros + step - 1) / step * step
