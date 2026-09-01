package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.compose.ui.interaction.TrimConstraint
import dev.jordond.filmstrip.edit.TimeRange
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class TrimOverlayTest {
  @Test
  fun `a drag keeps accumulating after its first step`() =
    runComposeUiTest {
      val scale = TimelineScale(DURATION, PIXELS_PER_SECOND)
      var range by mutableStateOf(TimeRange(Duration.ZERO, 20.seconds))

      setContent {
        Box(Modifier.size(width = 400.dp, height = 72.dp)) {
          TrimOverlay(
            range = range,
            scale = scale,
            onRangeChange = { range = it },
            constraint = TrimConstraint.MinDuration(1.seconds),
          )
        }
      }

      // Touch slop swallows an unknown first stretch, so the gesture is measured by what a later
      // stretch adds rather than by where it arrives. A handle that stops after its first step
      // adds nothing here, which is the regression this pins.
      onRoot().performTouchInput {
        down(Offset(0f, 36f))
        advanceEventTime(16L)
        moveTo(Offset(150f, 36f))
      }
      waitForIdle()
      val afterSlop = range.start

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(100f, 0f))
        up()
      }
      waitForIdle()

      // A pixel is a hundredth of a second here and the mapping runs through Float, so the arrival
      // is compared within a millisecond rather than exactly.
      ((range.start - afterSlop) - 1.seconds).absoluteValue shouldBeLessThan 2.milliseconds
    }

  @Test
  fun `a drag started after a zoom reports against the scale it was drawn at`() =
    runComposeUiTest {
      var pixelsPerSecond by mutableStateOf(PIXELS_PER_SECOND)
      var range by mutableStateOf(TimeRange(HANDLE_TIME, 20.seconds))

      setContent {
        Box(Modifier.size(width = 400.dp, height = 72.dp)) {
          TrimOverlay(
            range = range,
            scale = TimelineScale(DURATION, pixelsPerSecond),
            onRangeChange = { range = it },
            constraint = TrimConstraint.MinDuration(1.seconds),
          )
        }
      }

      // A handle's gesture coroutine is launched by the first pointer event it sees and runs from
      // there, so a zoom before it has ever been touched is one it picks up for free. Tapping it
      // where the first scale draws it is what gets the coroutine running with that scale.
      onRoot().performTouchInput {
        down(Offset(HANDLE_X, HANDLE_Y))
        advanceEventTime(16L)
        up()
      }
      waitForIdle()

      pixelsPerSecond = ZOOMED_PIXELS_PER_SECOND
      waitForIdle()

      onRoot().performTouchInput {
        down(Offset(ZOOMED_HANDLE_X, HANDLE_Y))
        advanceEventTime(16L)
        moveTo(Offset(ZOOMED_HANDLE_X + DRAG_PX, HANDLE_Y))
        up()
      }
      waitForIdle()

      // Touch slop swallows an unknown first stretch, so the arrival is bounded rather than named:
      // the handle is pushed right, by no more than the drag was long. A gesture still measuring
      // from the scale it started at takes its origin from half as many pixels per second and
      // lands the handle before where it began, whatever the slop turns out to be.
      range.start shouldBeGreaterThan HANDLE_TIME
      range.start shouldBeLessThanOrEqualTo HANDLE_TIME + DRAG_LIMIT
    }

  private companion object {
    val DURATION = 30.seconds
    const val PIXELS_PER_SECOND = 100f
    const val ZOOMED_PIXELS_PER_SECOND = 200f

    // Where the start handle sits before and after the zoom, which is HANDLE_TIME at each scale.
    val HANDLE_TIME = 1.seconds
    const val HANDLE_X = 100f
    const val ZOOMED_HANDLE_X = 200f
    const val HANDLE_Y = 36f

    // Comfortably past touch slop, short enough that the handle stays inside the box, and short of
    // the hundred pixels between the two scales' origins so the two readings cannot overlap.
    const val DRAG_PX = 80f
    val DRAG_LIMIT = (DRAG_PX / ZOOMED_PIXELS_PER_SECOND).toDouble().seconds
  }
}
