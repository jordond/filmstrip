package dev.jordond.filmstrip.compose.ui.interaction

import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import kotlin.time.Duration

/**
 * The calls one press, drag and release makes, in order.
 *
 * Split from the pointer loop that drives it so the ordering is a plain state machine rather than something only a real
 * gesture can exercise: a press begins exactly one scrub whatever arrives next, a drag outside one is ignored, and a
 * release ends the scrub once.
 *
 * @param scale What turns a viewport x into a source time.
 * @param scrollPx How far the timeline has scrolled, in content pixels.
 * @param sourceOffset Where the player's zero sits on the timeline's clock, so
 * `timelineTime == playerTime + sourceOffset()` .
 * @param onStart Called once, as the gesture begins.
 * @param onSeek Called with the player time under the pointer, for the press and for every drag.
 * @param onEnd Called once, as the gesture ends or is cancelled.
 */
internal class ScrubGesture(
  private val scale: TimelineScale,
  private val scrollPx: () -> Float,
  private val sourceOffset: () -> Duration = { Duration.ZERO },
  private val onStart: () -> Unit,
  private val onSeek: (Duration) -> Unit,
  private val onEnd: () -> Unit,
) {
  private var isActive = false

  fun press(x: Float) {
    if (isActive) return
    isActive = true
    onStart()
    onSeek(timeAt(x))
  }

  fun drag(x: Float) {
    if (!isActive) return
    onSeek(timeAt(x))
  }

  fun release() {
    if (!isActive) return
    isActive = false
    onEnd()
  }

  private fun timeAt(x: Float): Duration = (scale.timeAt(scrollPx() + x) - sourceOffset()).coerceAtLeast(Duration.ZERO)
}
