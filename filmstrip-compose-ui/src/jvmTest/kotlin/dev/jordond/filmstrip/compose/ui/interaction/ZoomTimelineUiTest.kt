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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.TimelineState
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.rememberTimelineState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class ZoomTimelineUiTest {
  @Test
  fun `a one finger drag scrolls the strip and does not change the zoom`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent { state = zoomable() }

      onRoot().performTouchInput {
        down(Offset(200f, 24f))
        advanceEventTime(16L)
        moveTo(Offset(60f, 24f))
        up()
      }
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(3)
      state.scrollPx() shouldNotBe 0f
    }

  @Test
  fun `a two finger pinch steps the zoom and holds the focal point rather than scrolling`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent { state = zoomable() }

      onRoot().performTouchInput {
        down(0, Offset(150f, 24f))
        down(1, Offset(250f, 24f))
        advanceEventTime(16L)
        // Both fingers spread apart by the same amount, doubling the distance between them, so
        // this is exactly one step on the ladder.
        updatePointerTo(0, Offset(100f, 24f))
        updatePointerTo(1, Offset(300f, 24f))
        move()
        up(0)
        up(1)
      }
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(4)
      // The focal point (the centroid, at 200px) held still puts the scroll at exactly 200px. A
      // pinch mistaken for a two finger drag would leave it wherever that stray drag pushed it.
      state.scrollPx() shouldBe 200f
    }

  @Composable
  private fun zoomable(): TimelineState {
    val density = LocalDensity.current
    val state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))

    Box(
      Modifier
        .size(width = 400.dp, height = 48.dp)
        .zoomTimeline(state),
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

  private companion object {
    val DURATION = 60.seconds
    const val VIEWPORT_WIDTH_PX = 400f
    const val TILE_WIDTH_PX = 46
  }
}
