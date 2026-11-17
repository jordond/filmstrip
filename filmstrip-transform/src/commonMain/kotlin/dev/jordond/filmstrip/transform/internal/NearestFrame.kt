package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
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
 * A span that opens between two slots can have the slot nearest its start just before it. That slot reads back from
 * [trimStart] by the time it sits before the span, scaled by [speed], and never earlier than [runStart]. [runStart] is
 * where the run of clips this one plays on from opens on the source, which is [trimStart] for a clip that plays on from
 * none, so that clip's first slot reads its trim start and no earlier.
 */
@InternalFilmstripApi
public fun sourceTimeOfSlot(
  trimStart: Duration,
  spanStart: Duration,
  speed: Double,
  slot: Long,
  frameRate: Int,
  runStart: Duration,
): Duration = maxOf(runStart, trimStart + sourceOffsetAt(outputSlotTime(slot, frameRate) - spanStart, speed))

/**
 * The earliest source time this clip's slots read on a grid of [frameRate], which is its trim start unless it plays on
 * from the clip before it and its first slot sits ahead of its span.
 */
@InternalFilmstripApi
public fun ResolvedClip.earliestReadTime(frameRate: Int): Duration {
  val first = outputSlotOf(span.start, frameRate)
  return minOf(start, sourceTimeOfSlot(start, span.start, speed, first, frameRate, runStart))
    .coerceAtLeast(Duration.ZERO)
}
