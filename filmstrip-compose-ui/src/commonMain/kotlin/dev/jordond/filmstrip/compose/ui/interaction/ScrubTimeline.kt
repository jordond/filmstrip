package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import kotlin.time.Duration

/**
 * Tap and drag over a timeline, through the scrub protocol the player needs.
 *
 * A press begins a scrub and seeks to what is under it, every drag seeks again inside that scrub, and lifting off ends
 * it and settles on the exact frame. Seeking on each drag delta without this cancels its own in-flight seeks, so the
 * decoder never catches up and the picture stops moving while the finger does.
 *
 * A gesture that ends without lifting off still ends the scrub, whether the pointer is cancelled or the timeline leaves
 * the composition under the finger, so the player is never left scrubbing with nothing driving it.
 *
 * ```
 * val scrub = rememberScrubState(player)
 *
 * Column {
 *   TimelineRuler(
 *     scale = timeline.scale,
 *     modifier = Modifier.scrubTimeline(scrub, timeline.scale, timeline::scrollPx),
 *     scrollPx = timeline::scrollPx,
 *   )
 *
 *   FilmstripStrip(frames = frames, grid = timeline.grid, state = timeline.listState)
 * }
 * ```
 *
 * @param scrub The protocol the gesture drives.
 * @param scale What turns the gesture's x into a source time.
 * @param scrollPx How far the timeline under the gesture has scrolled. The lambda itself is fixed when the gesture's
 * coroutine starts, so read the values it needs inside it. One that closes over a number read during composition keeps
 * that number for the whole gesture.
 * @param sourceOffset Where the player's zero sits on the timeline's clock, so
 * `timelineTime == playerTime + sourceOffset()` . Read inside the gesture for the same reason as [scrollPx], so a trim
 * handle moving mid-gesture is honoured by the next drag.
 */
public fun Modifier.scrubTimeline(
  scrub: ScrubState,
  scale: TimelineScale,
  scrollPx: () -> Float = { 0f },
  sourceOffset: () -> Duration = { Duration.ZERO },
): Modifier =
  pointerInput(scrub, scale) {
    awaitEachGesture {
      val gesture =
        ScrubGesture(
          scale = scale,
          scrollPx = scrollPx,
          sourceOffset = sourceOffset,
          onStart = scrub::onScrubStart,
          onSeek = scrub::onScrubTo,
          onEnd = scrub::onScrubEnd,
        )

      val down = awaitFirstDown(requireUnconsumed = false)
      down.consume()

      try {
        gesture.press(down.position.x)

        while (true) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull { it.id == down.id } ?: break
          if (!change.pressed) break
          if (change.positionChanged()) {
            gesture.drag(change.position.x)
            change.consume()
          }
        }
      } finally {
        gesture.release()
      }
    }
  }
