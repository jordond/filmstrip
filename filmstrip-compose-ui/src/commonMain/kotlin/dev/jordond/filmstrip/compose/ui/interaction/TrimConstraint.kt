package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.TimeRange
import kotlin.time.Duration

/**
 * Which end of a trim a gesture is moving.
 *
 * A closed type: both ends of a range is all there is.
 */
public enum class TrimHandle {
  /**
   * The handle at the start of the range.
   */
  Start,

  /**
   * The handle at the end of the range.
   */
  End,
}

/**
 * What a trim gesture is allowed to produce.
 *
 * A closed type. The three modes are genuinely different rules rather than combinations of a nullable minimum and
 * maximum, most of whose combinations mean nothing.
 *
 * Immutable, so a timeline holding one as an option stays skippable.
 */
@Immutable
public sealed interface TrimConstraint {
  /**
   * Corrects [proposed] into a range this constraint allows.
   *
   * @param proposed What the gesture asked for.
   * @param handle Which end of the range moved.
   * @param duration How long the source is, which no range may run past.
   * @return The corrected range.
   */
  public fun constrain(
    proposed: TimeRange,
    handle: TrimHandle,
    duration: Duration,
  ): TimeRange

  /**
   * A range that may be any length at or above [minimum].
   *
   * @property minimum How short the range may get.
   */
  @Poko
  public class MinDuration(
    public val minimum: Duration,
  ) : TrimConstraint {
    override fun constrain(
      proposed: TimeRange,
      handle: TrimHandle,
      duration: Duration,
    ): TimeRange = proposed.clampedTo(minimum, duration, duration, handle)
  }

  /**
   * A range between [minimum] and [maximum] long.
   *
   * @property minimum How short the range may get.
   * @property maximum How long the range may get.
   */
  @Poko
  public class MinMaxDuration(
    public val minimum: Duration,
    public val maximum: Duration,
  ) : TrimConstraint {
    override fun constrain(
      proposed: TimeRange,
      handle: TrimHandle,
      duration: Duration,
    ): TimeRange = proposed.clampedTo(minimum, maximum, duration, handle)
  }

  /**
   * A range of exactly [length], where moving either end slides the whole window.
   *
   * @property length How long the range always is.
   */
  @Poko
  public class FixedDuration(
    public val length: Duration,
  ) : TrimConstraint {
    override fun constrain(
      proposed: TimeRange,
      handle: TrimHandle,
      duration: Duration,
    ): TimeRange {
      val span = length.coerceIn(Duration.ZERO, duration)
      return when (handle) {
        TrimHandle.Start -> {
          val start = proposed.start.coerceIn(Duration.ZERO, duration - span)
          TimeRange(start, start + span)
        }
        TrimHandle.End -> {
          val end = proposed.endOr(duration).coerceIn(span, duration)
          TimeRange(end - span, end)
        }
      }
    }
  }
}

/**
 * [TimeRange.endExclusive], or [duration] for a range left open.
 */
private fun TimeRange.endOr(duration: Duration): Duration = endExclusive ?: duration

/**
 * The range with the moved end pulled back inside a length of `minimum..maximum` .
 *
 * The end that did not move stays where it is, so a handle dragged past its limit stops rather than pushing the other
 * one along.
 */
private fun TimeRange.clampedTo(
  minimum: Duration,
  maximum: Duration,
  duration: Duration,
  handle: TrimHandle,
): TimeRange {
  val shortest = minimum.coerceIn(Duration.ZERO, duration)
  val longest = maximum.coerceIn(shortest, duration)

  return when (handle) {
    TrimHandle.Start -> {
      // The end is pushed out first where there is not enough room behind it for the shortest
      // range, so the handle that moved is clamped against an end the minimum can actually fit.
      val end = endOr(duration).coerceIn(shortest, duration)
      val lowest = (end - longest).coerceAtLeast(Duration.ZERO)
      val highest = (end - shortest).coerceAtLeast(lowest)
      TimeRange(start.coerceIn(lowest, highest), end)
    }
    TrimHandle.End -> {
      val begin = start.coerceIn(Duration.ZERO, (duration - shortest).coerceAtLeast(Duration.ZERO))
      val lowest = (begin + shortest).coerceAtMost(duration)
      val highest = (begin + longest).coerceIn(lowest, duration)
      TimeRange(begin, endOr(duration).coerceIn(lowest, highest))
    }
  }
}
