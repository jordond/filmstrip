package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

// Which source frame an output slot shows, derived once. Every backend draws this frame on a slot, in the file and in
// the preview.

/**
 * The index of the frame in [times] nearest [target], or null when [times] is empty.
 *
 * [times] are a source's frame times in presentation order. A target before the first frame or past the last takes
 * that end's frame.
 *
 * A target within [NEAREST_FRAME_TIE] of half way between two frames is a tie, and a tie takes the earlier frame. Half
 * way between two whole microsecond times can itself sit half a microsecond off, which this reads as a tie too.
 */
@InternalFilmstripApi
public fun nearestSourceFrame(
  times: List<Duration>,
  target: Duration,
): Int? {
  if (times.isEmpty()) return null
  var low = 0
  var high = times.size
  while (low < high) {
    val middle = (low + high) / 2
    if (times[middle] <= target) low = middle + 1 else high = middle
  }
  val after = low
  val before = after - 1
  return when {
    before < 0 -> after
    after == times.size -> before
    (target - times[before]) - (times[after] - target) > NEAREST_FRAME_TIE * 2 -> after
    else -> before
  }
}

/**
 * How far from half way between two frames a target still reads as a tie in [nearestSourceFrame], one step of the
 * microseconds every time is counted in.
 */
@InternalFilmstripApi
public val NEAREST_FRAME_TIE: Duration = 1.microseconds

/**
 * The source time output [slot] shows of a clip whose trim opens at [trimStart] and whose span on the timeline opens
 * at [spanStart], running at [speed] on a grid of [frameRate].
 *
 * A slot before the span reads the trim's own start.
 */
@InternalFilmstripApi
public fun sourceTimeOfSlot(
  trimStart: Duration,
  spanStart: Duration,
  speed: Double,
  slot: Long,
  frameRate: Int,
): Duration =
  trimStart + sourceOffsetAt((outputSlotTime(slot, frameRate) - spanStart).coerceAtLeast(Duration.ZERO), speed)
