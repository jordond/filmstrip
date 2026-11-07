package dev.jordond.filmstrip.compose.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.compose.ui.component.scrollStripTo
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.timelineKeys
import dev.jordond.filmstrip.compose.ui.interaction.wheelZoomTimeline
import dev.jordond.filmstrip.compose.ui.interaction.zoomTimeline
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class TimelineViewportFitTest {
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
      settle()

      state.zoom shouldBe TimelineZoom.of(5)
    }

  @Test
  fun `a null zoom refits at each new viewport`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }
      val built = state

      listOf(390f, 1000f, 1272f, 1920f, 700f).forEach { width ->
        viewportWidthPx = width
        settle()

        withClue("at $width px") {
          state shouldBe built
          state.zoom shouldBe TimelineZoom.fitting(DURATION, width)
          state.scale.contentWidthPx shouldBeLessThanOrEqual width
          (width - state.scale.contentWidthPx) shouldBeLessThanOrEqual 1f
        }
      }
    }

  @Test
  fun `a width still changing keeps the zoom until it holds`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }
      val fit = state.zoom
      val positions = state.grid.positions.toList()

      // A drag's worth of widths, closer together than the settle.
      (802..1000 step 2).forEach { width ->
        viewportWidthPx = width.toFloat()
        waitForIdle()
        mainClock.advanceTimeBy(16)
        waitForIdle()

        withClue("at $width px") {
          state.zoom shouldBe fit
          state.grid.positions.toList() shouldBe positions
        }
      }

      settle()

      state.zoom shouldBe TimelineZoom.fitting(DURATION, 1000f)
    }

  @Test
  fun `a single new width holds its fit back for the settle and no longer`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }
      val fit = state.zoom

      viewportWidthPx = RESIZED_WIDTH_PX
      waitForIdle()
      state.zoom shouldBe fit

      settle()
      state.zoom shouldBe TimelineZoom.fitting(DURATION, RESIZED_WIDTH_PX)
    }

  @Test
  fun `a zoom during the settle wins over the refit it would have had`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }

      viewportWidthPx = RESIZED_WIDTH_PX
      waitForIdle()
      state.zoomIn(focusViewportPx = 50f)
      val zoomed = state.zoom

      settle()

      zoomed shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX).zoomedIn()
      state.zoom shouldBe zoomed
    }

  @Test
  fun `a state built at a new width fits it at once`() =
    runComposeUiTest {
      var duration by mutableStateOf(DURATION)
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(duration, viewportWidthPx, TILE_WIDTH_PX)
      }

      viewportWidthPx = RESIZED_WIDTH_PX
      duration = 90.seconds
      waitForIdle()

      state.zoom shouldBe TimelineZoom.fitting(90.seconds, RESIZED_WIDTH_PX)
    }

  @Test
  fun `a scroll left over from a timeline too long to fit lands at the start of the refit`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(100f)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }

      state.listState.scrollStripTo(state.grid, 20f)
      waitForIdle()
      state.scrollPx() shouldBe 20f

      viewportWidthPx = VIEWPORT_WIDTH_PX
      settle()

      state.zoom shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX)
      state.scrollPx() shouldBe 0f
    }

  @Test
  fun `a viewport change leaves a zoom alone after zoomIn`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }

      state.zoomIn(focusViewportPx = 50f)
      waitForIdle()
      val zoomed = state.zoom
      zoomed shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX).zoomedIn()

      viewportWidthPx = RESIZED_WIDTH_PX
      settle()

      state.zoom shouldBe zoomed
    }

  @Test
  fun `a viewport change leaves a zoom alone after zoomOut`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }

      state.zoomOut(focusViewportPx = 50f)
      waitForIdle()
      val zoomed = state.zoom
      zoomed shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX).zoomedOut()

      viewportWidthPx = RESIZED_WIDTH_PX
      settle()

      state.zoom shouldBe zoomed
    }

  @Test
  fun `zooming out at the bottom step leaves the fit following the viewport`() =
    runComposeUiTest {
      val duration = 180.seconds
      var viewportWidthPx by mutableStateOf(390f)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(duration, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }
      val fit = state.zoom
      fit.step shouldBe TimelineZoom.Steps.first

      state.zoomOut(focusViewportPx = 50f)
      waitForIdle()
      state.zoom shouldBe fit

      viewportWidthPx = VIEWPORT_WIDTH_PX
      settle()

      state.zoom shouldBe TimelineZoom.fitting(duration, VIEWPORT_WIDTH_PX)
    }

  @Test
  fun `zooming in at the top step leaves the fit following the viewport`() =
    runComposeUiTest {
      val duration = 100.milliseconds
      var viewportWidthPx by mutableStateOf(390f)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(duration, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx)
      }
      val fit = state.zoom
      fit.step shouldBe TimelineZoom.Steps.last

      state.zoomIn(focusViewportPx = 50f)
      waitForIdle()
      state.zoom shouldBe fit

      viewportWidthPx = VIEWPORT_WIDTH_PX
      settle()

      state.zoom shouldBe TimelineZoom.fitting(duration, VIEWPORT_WIDTH_PX)
    }

  @Test
  fun `a non null zoom never refits`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      var zoom by mutableStateOf<TimelineZoom?>(TimelineZoom.of(6))
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX, zoom = zoom)
      }

      viewportWidthPx = RESIZED_WIDTH_PX
      settle()
      state.zoom shouldBe TimelineZoom.of(6)

      // The host letting go of the zoom leaves the state where the host put it.
      zoom = null
      waitForIdle()
      viewportWidthPx = VIEWPORT_WIDTH_PX
      settle()
      state.zoom shouldBe TimelineZoom.of(6)
    }

  @Test
  fun `a host zoom arriving after a fit stops the refitting`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      var zoom by mutableStateOf<TimelineZoom?>(null)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX, zoom = zoom)
      }
      state.zoom shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX)

      zoom = TimelineZoom.of(6)
      waitForIdle()
      zoom = null
      waitForIdle()
      viewportWidthPx = RESIZED_WIDTH_PX
      settle()

      state.zoom shouldBe TimelineZoom.of(6)
    }

  @Test
  fun `a pinch hands the zoom to the user`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx, Modifier.zoomTimeline(state))
      }

      onRoot().performTouchInput {
        down(0, Offset(150f, 24f))
        down(1, Offset(250f, 24f))
        advanceEventTime(16L)
        updatePointerTo(0, Offset(100f, 24f))
        updatePointerTo(1, Offset(300f, 24f))
        move()
        up(0)
        up(1)
      }
      waitForIdle()

      zoomStaysThroughAResize(state, resize = { viewportWidthPx = it })
    }

  @Test
  fun `a wheel zoom hands the zoom to the user`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        strip(state, viewportWidthPx, Modifier.wheelZoomTimeline(state, requireModifier = false))
      }

      onRoot().performMouseInput {
        moveTo(Offset(200f, 24f))
        scroll(-3f)
      }
      waitForIdle()

      zoomStaysThroughAResize(state, resize = { viewportWidthPx = it })
    }

  @Test
  fun `a zoom key hands the zoom to the user`() =
    runComposeUiTest {
      var viewportWidthPx by mutableStateOf(VIEWPORT_WIDTH_PX)
      lateinit var state: TimelineState
      setContent {
        state = rememberTimelineState(DURATION, viewportWidthPx, TILE_WIDTH_PX)
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        strip(
          state,
          viewportWidthPx,
          Modifier
            .focusRequester(focusRequester)
            .timelineKeys(state, position = { Duration.ZERO }),
        )
      }
      waitForIdle()

      onRoot().performKeyInput { pressKey(Key.ZoomIn) }
      waitForIdle()

      zoomStaysThroughAResize(state, resize = { viewportWidthPx = it })
    }

  /**
   * Runs past [REFIT_SETTLE], so a width set just before this has had its chance to refit.
   */
  private fun ComposeUiTest.settle() {
    waitForIdle()
    mainClock.advanceTimeBy(REFIT_SETTLE.inWholeMilliseconds + 100)
    waitForIdle()
  }

  /**
   * Checks the gesture just performed stepped [state] in from its fit, then that a resize leaves the zoom where it is.
   */
  private fun ComposeUiTest.zoomStaysThroughAResize(
    state: TimelineState,
    resize: (Float) -> Unit,
  ) {
    val zoomed = state.zoom
    zoomed shouldBe TimelineZoom.fitting(DURATION, VIEWPORT_WIDTH_PX).zoomedIn()

    resize(RESIZED_WIDTH_PX)
    settle()

    state.zoom shouldBe zoomed
  }

  private companion object {
    val DURATION = 60.seconds
    const val VIEWPORT_WIDTH_PX = 800f
    const val RESIZED_WIDTH_PX = 1300f
    const val TILE_WIDTH_PX = 46
  }
}
