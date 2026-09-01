package dev.jordond.filmstrip.edit

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * A half-open range of media time, `[start, endExclusive)`.
 *
 * Half-open so ranges concatenate without sharing a frame: the clip ending at ten seconds and the
 * clip starting at ten seconds overlap on no sample.
 *
 * @property start Where the range starts.
 * @property endExclusive Where the range ends, or null to run to the end of the source.
 */
@Serializable
@Poko
public class TimeRange(
  public val start: Duration,
  public val endExclusive: Duration? = null,
) {
  /**
   * How long the range covers, or null while the end is open and the source has not been probed.
   */
  public val duration: Duration? = endExclusive?.let { it - start }

  /**
   * Tests a media time against the range.
   *
   * @param time The media time to test.
   * @return True when [time] falls inside the range. An open-ended range contains every time at or
   *   after [start].
   */
  public operator fun contains(time: Duration): Boolean = time >= start && (endExclusive == null || time < endExclusive)

  public companion object {
    /**
     * Everything from [start] to the end of the source.
     */
    public fun from(start: Duration): TimeRange = TimeRange(start, null)

    /**
     * A range covering `[start, endExclusive)`.
     */
    public fun of(
      start: Duration,
      endExclusive: Duration,
    ): TimeRange = TimeRange(start, endExclusive)
  }
}

/**
 * A range covering [range], with its end treated as exclusive.
 */
public fun TimeRange(range: ClosedRange<Duration>): TimeRange = TimeRange(range.start, range.endInclusive)

/**
 * How long a still is held once [trim] is applied, given the [held] span its source names.
 *
 * A still shows the same pixels throughout, so a trim over one takes nothing away but length. The
 * trim is resolved against [held] rather than extending past it, and a trim starting at or after
 * the end of the held span resolves to zero.
 */
@InternalFilmstripApi
public fun stillHold(
  held: Duration,
  trim: TimeRange?,
): Duration {
  val start = trim?.start ?: Duration.ZERO
  val end = (trim?.endExclusive ?: held).coerceAtMost(held)
  return (end - start).coerceAtLeast(Duration.ZERO)
}
