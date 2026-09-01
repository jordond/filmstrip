package dev.jordond.filmstrip.compose.ui.geometry

import dev.drewhamilton.poko.Poko
import kotlin.math.floor
import kotlin.math.ln
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A zoom level, as one of a fixed ladder of steps.
 *
 * Each step doubles [pixelsPerSecond]. A [StripGrid] samples its tiles at their leading edges, so every position at one
 * step is still a position at the next one in, and a strip that zooms keeps the frames it has already decoded and asks
 * only for the ones that fall between them.
 *
 * A timeline handed a zoom stops pinching, since the host now owns the ladder. Stepping it from outside holds no focal
 * point still either, which is what `TimelineState.zoomTo` is for.
 *
 * ```
 * var zoom by remember(duration) { mutableStateOf(TimelineZoom.fitting(duration, viewportWidthPx)) }
 *
 * Column {
 *   FilmstripTimeline(
 *     // ...
 *     zoom = zoom,
 *   )
 *
 *   Row {
 *     Button(
 *       onClick = { zoom = zoom.zoomedOut() },
 *       enabled = zoom.step > TimelineZoom.Steps.first,
 *     ) { Text("Zoom out") }
 *
 *     Button(
 *       onClick = { zoom = zoom.zoomedIn() },
 *       enabled = zoom.step < TimelineZoom.Steps.last,
 *     ) { Text("Zoom in") }
 *   }
 * }
 * ```
 *
 * @property step Where on the ladder this sits, in `Steps` .
 */
@Poko
public class TimelineZoom private constructor(
  public val step: Int,
) {
  /**
   * How wide one second is drawn at this step.
   */
  public val pixelsPerSecond: Float =
    run {
      var value = BASE_PIXELS_PER_SECOND
      repeat(step) { value *= 2f }
      value
    }

  /**
   * The next step in, or this one at the top of the ladder.
   */
  public fun zoomedIn(): TimelineZoom = of(step + 1)

  /**
   * The next step out, or this one at the bottom of the ladder.
   */
  public fun zoomedOut(): TimelineZoom = of(step - 1)

  /**
   * The scale this step draws [duration] at.
   */
  public fun scaleFor(duration: Duration): TimelineScale = TimelineScale(duration, pixelsPerSecond)

  public companion object {
    /**
     * Every step the ladder has, from the widest view out to the closest one in.
     */
    public val Steps: IntRange = 0..10

    /**
     * The step whose content is the widest that still fits in [viewportWidthPx].
     *
     * @param duration How much source time the timeline covers.
     * @param viewportWidthPx How wide the timeline is drawn.
     * @return The fitting step, and the bottom of the ladder for a timeline too long to fit at any of them.
     */
    public fun fitting(
      duration: Duration,
      viewportWidthPx: Float,
    ): TimelineZoom {
      val seconds = duration.toDouble(DurationUnit.SECONDS)
      if (seconds <= 0.0 || viewportWidthPx <= 0f) return of(Steps.first)

      val target = viewportWidthPx / seconds / BASE_PIXELS_PER_SECOND
      if (target <= 0.0) return of(Steps.first)
      return of(floor(ln(target) / LN_2).toInt())
    }

    /**
     * The step at [step], clamped into [Steps].
     */
    public fun of(step: Int): TimelineZoom = TimelineZoom(step.coerceIn(Steps))

    /**
     * How wide one second is drawn at the bottom of the ladder.
     *
     * An hour of source is a little over seven thousand content pixels there, which is the widest view worth offering:
     * everything below it is narrower than a scroll bar.
     */
    private const val BASE_PIXELS_PER_SECOND = 2f

    private val LN_2 = ln(2.0)
  }
}
