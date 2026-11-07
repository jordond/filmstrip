package dev.jordond.filmstrip.compose.ui.geometry

import dev.drewhamilton.poko.Poko
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A zoom level, as one step on a ladder that doubles.
 *
 * Each step doubles [pixelsPerSecond]. A [StripGrid] samples its tiles at their leading edges, so every position at one
 * step is still a position at the next one in, and a strip that zooms keeps the frames it has already decoded and asks
 * only for the ones that fall between them.
 *
 * [of] reads the ladder at its own numbers. [fitting] scales the whole ladder so its step lands exactly on the
 * viewport, and [zoomedIn] and [zoomedOut] stay on that scaled ladder. A fit and [of] at the same [step] are only equal
 * when the fit landed on a step of the unscaled ladder.
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
  private val anchor: Float,
) {
  /**
   * How wide one second is drawn at this step.
   */
  public val pixelsPerSecond: Float =
    run {
      var value = BASE_PIXELS_PER_SECOND * anchor
      repeat(step) { value *= 2f }
      value
    }

  /**
   * The next step in, or this one at the top of the ladder.
   */
  public fun zoomedIn(): TimelineZoom = TimelineZoom((step + 1).coerceIn(Steps), anchor)

  /**
   * The next step out, or this one at the bottom of the ladder.
   */
  public fun zoomedOut(): TimelineZoom = TimelineZoom((step - 1).coerceIn(Steps), anchor)

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
     * The zoom whose content fills [viewportWidthPx] without going past it.
     *
     * The step is the widest one that still fits, and the ladder is scaled so that step draws [duration] across the
     * whole viewport. A duration too short for the top step scales the ladder further and still fills. The content
     * is fitted to the viewport's whole pixels, since tiles are laid out in them and a viewport measured through `Dp`
     * can land a rounding error past its pixel.
     *
     * @param duration How much source time the timeline covers.
     * @param viewportWidthPx How wide the timeline is drawn.
     * @return The fit, and the bottom of the unscaled ladder for a timeline too long to fit at any step or when there
     * is no duration or finite width to fit.
     */
    public fun fitting(
      duration: Duration,
      viewportWidthPx: Float,
    ): TimelineZoom {
      val seconds = duration.toDouble(DurationUnit.SECONDS)
      if (seconds <= 0.0 || viewportWidthPx <= 0f || !viewportWidthPx.isFinite()) return of(Steps.first)

      val wholePixels = floor(viewportWidthPx + PIXEL_ROUNDING_ERROR)
      var anchor = wholePixels / seconds / BASE_PIXELS_PER_SECOND
      if (anchor < 1.0) return of(Steps.first)

      var step = Steps.first
      while (anchor >= 2.0 && step < Steps.last) {
        anchor /= 2.0
        step++
      }
      return TimelineZoom(step, anchor.toFloatRoundedDown())
    }

    /**
     * The step at [step], clamped into [Steps].
     */
    public fun of(step: Int): TimelineZoom = TimelineZoom(step.coerceIn(Steps), anchor = 1f)

    /**
     * How wide one second is drawn at the bottom of the ladder.
     *
     * An hour of source is a little over seven thousand content pixels there, which is the widest view worth offering:
     * everything below it is narrower than a scroll bar.
     */
    private const val BASE_PIXELS_PER_SECOND = 2f

    /**
     * The most a width converted from pixels to `Dp` and back can sit away from the whole pixel it started on.
     */
    private const val PIXEL_ROUNDING_ERROR = 0.01f

    /**
     * The closest [Float] at or below this value, so a scale built from it cannot draw past what it was fitted to.
     */
    private fun Double.toFloatRoundedDown(): Float {
      val nearest = toFloat()
      return if (nearest.toDouble() > this) Float.fromBits(nearest.toRawBits() - 1) else nearest
    }
  }
}
