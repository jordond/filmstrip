package dev.jordond.filmstrip.compose.ui.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The mapping between source time and content pixels at one zoom level.
 *
 * The ruler, the tiles, the playhead and the trim handles all read their geometry from this, so a strip that scrolls
 * cannot drift out of agreement with what is drawn over it.
 *
 * Content pixels run from zero at the start of the composition to [contentWidthPx] at its end, which is wider than the
 * viewport at any zoom the whole strip does not fit in. Subtract the scroll offset to reach viewport pixels.
 *
 * ```
 * val scale = TimelineZoom.fitting(duration, viewportWidthPx).scaleFor(duration)
 *
 * Canvas(Modifier.fillMaxSize()) {
 *   val x = scale.xOf(marker) - scrollPx()
 *   if (x in 0f..size.width) {
 *     drawLine(color, Offset(x, 0f), Offset(x, size.height))
 *   }
 * }
 * ```
 *
 * @property duration How much source time the timeline covers.
 * @property pixelsPerSecond How wide one second of that is drawn.
 */
@Poko
public class TimelineScale(
  public val duration: Duration,
  public val pixelsPerSecond: Float,
) {
  /**
   * How much time the timeline covers, never less than nothing.
   *
   * [duration] is whatever it was constructed with, so a caller reads back what it passed, and every mapping here works
   * from this instead.
   */
  private val span: Duration = duration.coerceAtLeast(Duration.ZERO)

  /**
   * How wide the whole timeline is, in content pixels.
   */
  public val contentWidthPx: Float =
    (span.toDouble(DurationUnit.SECONDS) * pixelsPerSecond).toFloat().coerceAtLeast(0f)

  /**
   * The source time drawn at [contentX], clamped into `Duration.ZERO..duration` .
   */
  public fun timeAt(contentX: Float): Duration {
    if (pixelsPerSecond <= 0f) return Duration.ZERO
    val seconds = (contentX / pixelsPerSecond).toDouble()
    return seconds.toDuration(DurationUnit.SECONDS).coerceIn(Duration.ZERO, span)
  }

  /**
   * Where [time] is drawn, in content pixels, clamped into `0f..contentWidthPx` .
   */
  public fun xOf(time: Duration): Float {
    val clamped = time.coerceIn(Duration.ZERO, span)
    return (clamped.toDouble(DurationUnit.SECONDS) * pixelsPerSecond).toFloat().coerceAtLeast(0f)
  }

  /**
   * How wide [span] is drawn, in content pixels.
   *
   * Unlike [xOf] this is a length rather than a coordinate, so it is not clamped to the timeline and a span longer than
   * [duration] measures what it would take.
   */
  public fun widthOf(span: Duration): Float = (span.toDouble(DurationUnit.SECONDS) * pixelsPerSecond).toFloat()

  /**
   * How far apart ruler ticks should sit, as the smallest step from [TICKS] that draws at least [minSpacingPx] apart.
   *
   * The unit follows the zoom rather than being fixed, so ticks stay legible from a whole hour on screen down to a
   * fraction of a second.
   *
   * @param minSpacingPx How close two ticks may be drawn, in pixels. Convert [FilmstripTimelineDefaults.MinTickSpacing]
   * through the local density rather than passing a fixed number, which is a different distance on every screen.
   * @return The chosen step, and the largest one in the ladder when even that is too close.
   */
  public fun tickInterval(minSpacingPx: Float): Duration {
    val last = TICKS.last()
    if (pixelsPerSecond <= 0f) return last
    return TICKS.firstOrNull { widthOf(it) >= minSpacingPx } ?: last
  }

  /**
   * The same timeline drawn at [pixelsPerSecond].
   */
  public fun withPixelsPerSecond(pixelsPerSecond: Float): TimelineScale = TimelineScale(duration, pixelsPerSecond)

  /**
   * The same zoom over [duration].
   */
  public fun withDuration(duration: Duration): TimelineScale = TimelineScale(duration, pixelsPerSecond)

  public companion object {
    /**
     * The ruler's tick steps, ascending.
     *
     * Every step divides the one above it, so zooming out folds ticks into their neighbours rather than shifting them
     * sideways. The finest is a tenth of a second, which is as fine as a label built by
     * [FilmstripTimelineDefaults.clockLabel] can be read.
     */
    private val TICKS: List<Duration> =
      listOf(
        100.toDuration(DurationUnit.MILLISECONDS),
        500.toDuration(DurationUnit.MILLISECONDS),
        1.toDuration(DurationUnit.SECONDS),
        5.toDuration(DurationUnit.SECONDS),
        10.toDuration(DurationUnit.SECONDS),
        30.toDuration(DurationUnit.SECONDS),
        1.toDuration(DurationUnit.MINUTES),
        5.toDuration(DurationUnit.MINUTES),
        10.toDuration(DurationUnit.MINUTES),
        30.toDuration(DurationUnit.MINUTES),
        1.toDuration(DurationUnit.HOURS),
      )
  }
}
