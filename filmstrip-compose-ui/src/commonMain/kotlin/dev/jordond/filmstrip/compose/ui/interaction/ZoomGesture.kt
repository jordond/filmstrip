package dev.jordond.filmstrip.compose.ui.interaction

/**
 * Turns a pinch's continuous zoom factor into steps on the zoom ladder.
 *
 * A pinch reports zoom as a factor since the last event rather than a total, and the ladder only understands doublings.
 * This folds every factor it is given into a running accumulator and spends it a step at a time: at or above 2.0 it
 * steps in and divides the accumulator by 2, at or below 0.5 it steps out and multiplies it by 2, so a pinch that
 * crosses several doublings in one motion steps more than once.
 *
 * Split from the pointer loop that drives it so the stepping logic is testable without a real gesture, the way
 * [ScrubGesture] is split out of `Modifier.scrubTimeline` .
 *
 * @param onZoomIn Called with the gesture's focal x, once for every step in.
 * @param onZoomOut Called with the gesture's focal x, once for every step out.
 */
internal class ZoomGesture(
  private val onZoomIn: (focalX: Float) -> Unit,
  private val onZoomOut: (focalX: Float) -> Unit,
) {
  private var accumulated = 1f

  /**
   * Folds [factor] into the running accumulator, focused on [focalX], stepping the ladder as many times as the
   * accumulator now crosses.
   *
   * A [factor] that is not finite or not positive is ignored rather than folded in: zero or a negative number would pin
   * the accumulator at a threshold forever, and an infinite one would stay infinite after every division, so either
   * turns the loop below into an infinite one.
   */
  fun accumulate(
    factor: Float,
    focalX: Float,
  ) {
    if (!factor.isFinite() || factor <= 0f) return

    accumulated *= factor
    while (accumulated >= STEP_THRESHOLD) {
      onZoomIn(focalX)
      accumulated /= STEP_THRESHOLD
    }
    while (accumulated <= 1f / STEP_THRESHOLD) {
      onZoomOut(focalX)
      accumulated *= STEP_THRESHOLD
    }
  }

  /**
   * Drops whatever a gesture has accumulated, so the next one starts from a clean ladder position.
   */
  fun reset() {
    accumulated = 1f
  }

  private companion object {
    const val STEP_THRESHOLD = 2f
  }
}
