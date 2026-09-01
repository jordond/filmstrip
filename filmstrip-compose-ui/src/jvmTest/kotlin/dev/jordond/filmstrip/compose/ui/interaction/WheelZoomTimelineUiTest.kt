package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.TimelineState
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.rememberTimelineState
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class WheelZoomTimelineUiTest {
  @Test
  fun `a modified wheel steps the ladder once and holds the pointer's x still`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent { state = wheelZoomable() }

      onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
      onRoot().performMouseInput {
        moveTo(Offset(200f, 24f))
        // Three notches summing to exactly the threshold, so this is one step, not several.
        scroll(-1f)
        scroll(-1f)
        scroll(-1f)
      }
      onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(4)
      // The pointer's x (200px) held still puts the scroll at exactly 200px, the same focal-point
      // arithmetic the pinch uses.
      state.scrollPx() shouldBe 200f
    }

  @Test
  fun `a plain wheel under requireModifier does not zoom and does not consume the event`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      var scrollConsumed: Boolean? = null
      setContent { state = probed(requireModifier = true) { scrollConsumed = it } }

      onRoot().performMouseInput {
        moveTo(Offset(200f, 24f))
        scroll(-10f)
      }
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(3)
      scrollConsumed shouldBe false
    }

  @Test
  fun `a modified wheel consumes the event so the strip does not also scroll`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      var scrollConsumed: Boolean? = null
      setContent { state = probed(requireModifier = true) { scrollConsumed = it } }

      onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
      onRoot().performMouseInput {
        moveTo(Offset(200f, 24f))
        scroll(-10f)
      }
      onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
      waitForIdle()

      scrollConsumed shouldBe true
    }

  @Test
  fun `requireModifier false zooms without a modifier held`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent { state = wheelZoomable(requireModifier = false) }

      onRoot().performMouseInput {
        moveTo(Offset(200f, 24f))
        scroll(-3f)
      }
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(4)
    }

  @Composable
  private fun wheelZoomable(requireModifier: Boolean = true): TimelineState {
    val density = LocalDensity.current
    val state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))

    Box(
      Modifier
        .size(width = 400.dp, height = 48.dp)
        .wheelZoomTimeline(state, requireModifier = requireModifier),
    ) {
      LazyRow(state = state.listState) {
        items(state.grid.count) { index ->
          val tileWidth = with(density) { state.grid.tileWidthPxAt(index).toDp() }
          Box(Modifier.width(tileWidth).fillMaxHeight().background(Color.DarkGray))
        }
      }
    }

    return state
  }

  /**
   * A timeline with a downstream pointer input that records whether the scroll it saw was already
   * consumed, which is what tells the two consuming tests apart from each other without depending
   * on the strip's own, asynchronous wheel-scroll animation.
   */
  @Composable
  private fun probed(
    requireModifier: Boolean,
    onScroll: (consumed: Boolean) -> Unit,
  ): TimelineState {
    val state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))

    Box(
      Modifier
        .size(width = 400.dp, height = 48.dp)
        .wheelZoomTimeline(state, requireModifier = requireModifier)
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (event.type == PointerEventType.Scroll) {
                onScroll(event.changes.first().isConsumed)
              }
            }
          }
        },
    )

    return state
  }

  private companion object {
    val DURATION = 60.seconds
    const val VIEWPORT_WIDTH_PX = 400f
    const val TILE_WIDTH_PX = 46
  }
}
