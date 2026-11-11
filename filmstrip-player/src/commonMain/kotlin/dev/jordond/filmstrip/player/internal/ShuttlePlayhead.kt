package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.ShuttleEdge
import kotlin.math.abs
import kotlin.time.Duration

/**
 * The largest magnitude a shuttle rate may have.
 */
internal const val MAX_SHUTTLE_RATE: Float = 32f

/**
 * Where a shuttle's playhead sits, and the edge it is held on.
 *
 * @property position The playhead, inside the bounds it was computed for.
 * @property edge The bound the shuttle ran into, or null while it is still moving.
 */
internal data class ShuttlePlayhead(
  val position: Duration,
  val edge: ShuttleEdge?,
)

/**
 * Checks a rate handed to a shuttle.
 *
 * @throws IllegalArgumentException when [rate] is zero, not finite, or larger in magnitude than [MAX_SHUTTLE_RATE].
 */
internal fun requireShuttleRate(rate: Float) {
  require(rate.isFinite() && rate != 0f && abs(rate) <= MAX_SHUTTLE_RATE) {
    "A shuttle rate is finite, not zero and at most $MAX_SHUTTLE_RATE in either direction, got $rate"
  }
}

/**
 * Computes a shuttle's playhead, [elapsed] after it set off from [origin] at [rate].
 *
 * The playhead moves [rate] seconds of composition for each second elapsed, backwards for a negative rate, and stops
 * at the bound it is travelling towards. An edge is reported from the moment the playhead reaches that bound. The
 * bound behind it never is, so a shuttle leaving an edge reads as moving.
 *
 * @param origin Where the playhead was when the shuttle began or was last re-based.
 * @param rate Composition seconds per second, as [requireShuttleRate] accepts.
 * @param elapsed Wall time since [origin] was taken.
 * @param bounds The times of the first and last frames the shuttle may show, as [shuttleBoundsOf] gives them.
 */
internal fun shuttlePlayheadAt(
  origin: Duration,
  rate: Float,
  elapsed: Duration,
  bounds: ClosedRange<Duration>,
): ShuttlePlayhead {
  val travelled = origin.coerceIn(bounds.start, bounds.endInclusive) + elapsed * rate.toDouble()
  return when {
    rate > 0f && travelled >= bounds.endInclusive -> ShuttlePlayhead(bounds.endInclusive, ShuttleEdge.End)
    rate < 0f && travelled <= bounds.start -> ShuttlePlayhead(bounds.start, ShuttleEdge.Start)
    else -> ShuttlePlayhead(travelled, null)
  }
}

/**
 * The span a shuttle may show in a composition [duration] long at [frameRate], as the times of its first and last
 * frames.
 *
 * That is the slots [playbackRange] plays, counted the way [loopWrapOf] counts a range's, or every slot of the
 * composition when there is no range or the range starts past the last one.
 */
@InternalFilmstripApi
public fun shuttleBoundsOf(
  duration: Duration,
  frameRate: Int,
  playbackRange: TimeRange?,
): ClosedRange<Duration> {
  val span =
    playbackRange?.let { loopWrapOf(it, duration, frameRate) }
      ?: loopWrapOf(TimeRange(Duration.ZERO), duration, frameRate)
      ?: return Duration.ZERO..Duration.ZERO
  return outputSlotTime(span.firstSlot, frameRate)..outputSlotTime(span.lastSlot, frameRate)
}
