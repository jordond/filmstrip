package dev.jordond.filmstrip.compose.ui.interaction

/**
 * Turns a wheel's scroll delta into steps on the zoom ladder.
 *
 * A wheel reports scroll as a small delta per notch or per frame of a trackpad's inertia rather than a total, and the
 * ladder only understands whole steps. This folds every delta it is given into a running accumulator and spends it a
 * step at a time, so a flick that reports many small deltas in a row steps once rather than once per delta.
 *
 * Scrolling up, which is a negative delta, steps in. Scrolling down steps out, which is the direction a map or a photo
 * viewer zooms under the same gesture.
 *
 * Split from the pointer loop that drives it for the same reason [ZoomGesture] is: the stepping logic is testable
 * without a real wheel event.
 *
 * @param onZoomIn Called with the gesture's focal x, once for every step in.
 * @param onZoomOut Called with the gesture's focal x, once for every step out.
 */
internal class WheelZoomGesture(
  private val onZoomIn: (focalX: Float) -> Unit,
  private val onZoomOut: (focalX: Float) -> Unit,
) {
  private var accumulated = 0f

  /**
   * Folds [delta] into the running accumulator, focused on [focalX], stepping the ladder as many times as the
   * accumulator now crosses.
   *
   * A [delta] that is not finite is ignored rather than folded in, which is the one input that would otherwise pin the
   * accumulator at infinity and step the ladder forever.
   */
  fun accumulate(
    delta: Float,
    focalX: Float,
  ) {
    if (!delta.isFinite()) return

    accumulated -= delta
    while (accumulated >= STEP_THRESHOLD) {
      onZoomIn(focalX)
      accumulated -= STEP_THRESHOLD
    }
    while (accumulated <= -STEP_THRESHOLD) {
      onZoomOut(focalX)
      accumulated += STEP_THRESHOLD
    }
  }

  /**
   * Drops whatever a gesture has accumulated, so the next one starts from a clean ladder position.
   */
  fun reset() {
    accumulated = 0f
  }

  private companion object {
    const val STEP_THRESHOLD = 3f
  }
}
