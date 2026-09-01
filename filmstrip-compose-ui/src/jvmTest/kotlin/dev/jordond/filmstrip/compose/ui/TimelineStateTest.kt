package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.component.scrollStripTo
import dev.jordond.filmstrip.compose.ui.component.stripScrollPx
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class TimelineStateTest {
  @Test
  fun `a null zoom fits the duration in the viewport`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX)
      }

      state.zoom shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX)
    }

  @Test
  fun `an explicit zoom wins over the fit, and the viewport plays no part in it`() =
    runComposeUiTest {
      val explicit = TimelineZoom.of(6)
      lateinit var state: TimelineState
      setContent {
        // A viewport this narrow would fit at the bottom of the ladder if it were consulted.
        state = rememberTimelineState(DURATION, viewportWidthPx = 1f, TILE_WIDTH_PX, zoom = explicit)
      }

      state.zoom shouldBe explicit
    }

  @Test
  fun `scale and grid follow an internal zoom change, mid ladder rather than at either end`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))
      }

      // 60s at 16px per second is 960px, which 46px tiles cover in 21.
      state.scale.pixelsPerSecond shouldBe 16f
      state.grid.count shouldBe 21
      val positionsBefore = state.grid.positions

      state.zoom = TimelineZoom.of(4)
      waitForIdle()

      // Doubling the zoom doubles the tile count and keeps every position the step below it asked
      // for, at twice the index.
      state.scale.pixelsPerSecond shouldBe 32f
      state.grid.count shouldBe 42
      state.grid.positions shouldNotBe positionsBefore
      positionsBefore.forEachIndexed { index, position -> state.grid.positions[index * 2] shouldBe position }
    }

  @Test
  fun `scrollPx reflects the list state's current scroll, not one captured at composition time`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))
        strip(state)
      }

      val scrollPx = state::scrollPx
      scrollPx() shouldBe 0f

      state.listState.scrollStripTo(state.grid, 250f)
      waitForIdle()

      scrollPx() shouldBe 250f
    }

  @Test
  fun `the list state survives a duration change, and the scroll position with it`() =
    runComposeUiTest {
      var duration by mutableStateOf(DURATION)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(duration, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))
        strip(state)
      }

      val listState = state.listState
      listState.scrollStripTo(state.grid, 250f)
      waitForIdle()

      duration = 90.seconds
      waitForIdle()

      state.listState shouldBe listState
      state.listState.stripScrollPx(state.grid) shouldBe 250f
      state.scale.duration shouldBe 90.seconds
    }

  @Test
  fun `zoomTo holds the timeline time under the focal point still`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))
        strip(state)
      }

      state.listState.scrollStripTo(state.grid, 160f)
      waitForIdle()

      // A viewport x in the middle of the strip and a step in the middle of the ladder, so the
      // test cannot pass by accident at either end of either range.
      state.zoomTo(TimelineZoom.of(4), focusViewportPx = 100f)
      waitForIdle()

      // 260 content px at 16px/s is 16.25s, which sits at 520 content px at 32px/s. Holding the
      // focal point still means the strip lands 100px short of that.
      state.zoom shouldBe TimelineZoom.of(4)
      state.scrollPx() shouldBe 420f
    }

  @Test
  fun `a viewport width change does not undo a zoom set through zoomTo`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state)
      }

      state.zoomTo(TimelineZoom.of(5), focusViewportPx = 50f)
      waitForIdle()
      state.zoom shouldBe TimelineZoom.of(5)

      // A resize, such as a window resize, must not refit the ladder and undo it.
      viewportWidthPx = 300f
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(5)
    }

  /**
   * A bare row over [state]'s grid, laid out so a scroll on [state]'s list state has real content to act on.
   */
  @Composable
  private fun strip(state: TimelineState) {
    val density = LocalDensity.current
    Box(Modifier.size(width = 400.dp, height = 72.dp)) {
      LazyRow(state = state.listState) {
        items(state.grid.count) { index ->
          val tileWidth = with(density) { state.grid.tileWidthPxAt(index).toDp() }
          Box(Modifier.width(tileWidth).fillMaxHeight().background(Color.DarkGray))
        }
      }
    }
  }

  private companion object {
    val DURATION = 60.seconds
    const val VIEWPORT_WIDTH_PX = 800f
    const val TILE_WIDTH_PX = 46
  }
}
