package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import dev.jordond.filmstrip.compose.ui.TimelineState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Pinches a timeline's zoom ladder, holding the pinch's focal point still on the strip.
 *
 * A one finger drag is left alone, so the strip underneath keeps scrolling normally. This only reads the gesture once a
 * second pointer joins, and stops again the moment the pointer count drops back below two.
 *
 * @param state The timeline the gesture zooms.
 * @param enabled Whether the gesture is active. A host that owns the zoom level itself passes false, so the pinch does
 * not fight it.
 */
public fun Modifier.zoomTimeline(
  state: TimelineState,
  enabled: Boolean = true,
): Modifier =
  pointerInput(state, enabled) {
    if (!enabled) return@pointerInput

    // zoomIn and zoomOut are suspend, and the gesture loop below runs on the restricted scope
    // detectTransformGestures-style detectors use, which can only await its own pointer events.
    // Launching them here, rather than awaiting them in place, is what lets the loop keep reading
    // the gesture while a step's scroll settles.
    coroutineScope {
      val gesture =
        ZoomGesture(
          onZoomIn = { focalX -> launch { state.zoomIn(focalX) } },
          onZoomOut = { focalX -> launch { state.zoomOut(focalX) } },
        )

      awaitEachGesture {
        do {
          // Read on the Initial pass, which reaches this modifier before the strip's own LazyRow
          // sees the event. Consuming here, only once a second pointer is down, is what stops a
          // one finger drag from ever reaching the row as a drag: this loop leaves it alone, and
          // the row gets it untouched.
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val pressed = event.changes.count { it.pressed }

          if (pressed >= 2) {
            val zoom = event.calculateZoom()
            if (zoom != 1f) {
              gesture.accumulate(zoom, event.calculateCentroid().x)
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          }
        } while (event.changes.any { it.pressed })

        gesture.reset()
      }
    }
  }
