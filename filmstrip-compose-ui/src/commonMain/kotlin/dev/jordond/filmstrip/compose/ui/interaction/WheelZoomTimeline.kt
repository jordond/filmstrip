package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.jordond.filmstrip.compose.ui.TimelineState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Steps a timeline's zoom ladder with a scroll wheel, holding the wheel's x still on the strip.
 *
 * Desktop and web hosts have no pinch, so a wheel is what steps the ladder there. Only the vertical scroll axis is
 * read, which is the axis every wheel and every trackpad reports.
 *
 * @param state The timeline the wheel zooms.
 * @param enabled Whether the wheel is read at all. A host that owns the zoom level itself passes false, so the wheel
 * does not fight it.
 * @param requireModifier Whether a wheel only zooms while Control, or Command on a Mac, is held. True leaves a plain
 * wheel free to scroll the strip, which is what a host wants when the timeline sits in a scrollable page. False makes
 * every wheel zoom.
 */
public fun Modifier.wheelZoomTimeline(
  state: TimelineState,
  enabled: Boolean = true,
  requireModifier: Boolean = true,
): Modifier =
  pointerInput(state, enabled, requireModifier) {
    if (!enabled) return@pointerInput

    // zoomIn and zoomOut are suspend, and this loop runs on the restricted scope
    // awaitPointerEventScope gives it, which can only await its own pointer events. Launching
    // them here, rather than awaiting them in place, is what lets the loop keep reading wheel
    // events while a step's scroll settles.
    coroutineScope {
      val gesture =
        WheelZoomGesture(
          onZoomIn = { focalX -> launch { state.zoomIn(focalX) } },
          onZoomOut = { focalX -> launch { state.zoomOut(focalX) } },
        )

      awaitPointerEventScope {
        while (true) {
          // Read on the Initial pass, which reaches this modifier before the strip's own
          // LazyRow sees the event, the same way the pinch does. Leaving the event unconsumed
          // when the modifier is not held is what lets a plain wheel still reach the row below.
          val event = awaitPointerEvent(PointerEventPass.Initial)
          if (event.type != PointerEventType.Scroll) continue

          val change = event.changes.firstOrNull() ?: continue
          if (requireModifier && !event.keyboardModifiers.zoomsTimeline()) continue

          gesture.accumulate(change.scrollDelta.y, change.position.x)
          change.consume()
        }
      }
    }
  }

/**
 * Whether Control, or Command on a Mac, is held.
 *
 * Checking both together, rather than picking one by platform, is what lets this stay common code: a Windows or Linux
 * host reads Control and a Mac host reads Command, and neither needs to know which platform it is running on.
 */
private fun PointerKeyboardModifiers.zoomsTimeline(): Boolean = isCtrlPressed || isMetaPressed
