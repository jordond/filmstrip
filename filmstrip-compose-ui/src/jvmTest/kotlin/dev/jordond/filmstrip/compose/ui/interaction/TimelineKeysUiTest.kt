package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.rememberScrubState
import dev.jordond.filmstrip.compose.ui.TimelineState
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.rememberTimelineState
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class TimelineKeysUiTest {
  @Test
  fun `an arrow key seeks by exactly step through ScrubState`() =
    runComposeUiTest {
      val seeks = mutableListOf<Duration>()
      setContent { keyable(seeks, initialPosition = 10.seconds, step = 2.seconds) }

      onRoot().performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()

      seeks.last() shouldBe 12.seconds
    }

  @Test
  fun `a shifted arrow key seeks by five times step`() =
    runComposeUiTest {
      val seeks = mutableListOf<Duration>()
      setContent { keyable(seeks, initialPosition = 20.seconds, step = 1.seconds) }

      onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
      waitForIdle()

      seeks.last() shouldBe 25.seconds
    }

  @Test
  fun `a seek clamps at the end of the composition`() =
    runComposeUiTest {
      val seeks = mutableListOf<Duration>()
      setContent { keyable(seeks, initialPosition = 29.5.seconds, step = 2.seconds, duration = 30.seconds) }

      onRoot().performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()

      seeks.last() shouldBe 30.seconds
    }

  @Test
  fun `a seek clamps at the start of the composition`() =
    runComposeUiTest {
      val seeks = mutableListOf<Duration>()
      setContent { keyable(seeks, initialPosition = 0.5.seconds, step = 2.seconds, duration = 30.seconds) }

      onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
      waitForIdle()

      seeks.last() shouldBe Duration.ZERO
    }

  @Test
  fun `home and end jump to the ends`() =
    runComposeUiTest {
      val seeks = mutableListOf<Duration>()
      setContent { keyable(seeks, initialPosition = 15.seconds, duration = 30.seconds) }

      onRoot().performKeyInput { pressKey(Key.MoveHome) }
      waitForIdle()
      seeks.last() shouldBe Duration.ZERO

      onRoot().performKeyInput { pressKey(Key.MoveEnd) }
      waitForIdle()
      seeks.last() shouldBe 30.seconds
    }

  @Test
  fun `the zoom keys work with a null scrub, and the seeking keys do nothing`() =
    runComposeUiTest {
      lateinit var state: TimelineState
      val seeks = mutableListOf<Duration>()
      setContent { state = keyable(seeks, scrubEnabled = false) }

      onRoot().performKeyInput { pressKey(Key.ZoomIn) }
      onRoot().performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()

      state.zoom shouldBe TimelineZoom.of(4)
      seeks shouldBe emptyList()
    }

  @Test
  fun `a held arrow's repeats reach the handler as one burst`() =
    runComposeUiTest {
      val seeks = mutableListOf<Duration>()
      var starts = 0
      var ends = 0
      setContent { keyable(seeks, onBegin = { starts++ }, onEnd = { ends++ }) }

      onRoot().performKeyInput {
        keyDown(Key.DirectionRight)
        // Past the 500ms an OS starts repeating a held key at, so this is a burst of several
        // KeyDowns with no KeyUp between them, not one press. `ArrowScrubGestureTest` and
        // `ArrowIdleSettleTest` pin down the exact start-once, settle-once state machine this
        // exercises; what this proves is that Compose's own key repeat actually reaches it.
        advanceEventTime(650L)
        keyUp(Key.DirectionRight)
      }
      waitForIdle()

      starts shouldBeGreaterThan 0
      ends shouldBeGreaterThan 0
      // The initial press plus at least a couple of the 50ms repeats after 500ms.
      seeks.size shouldBeGreaterThan 2
    }

  @Composable
  private fun keyable(
    seeks: MutableList<Duration>,
    initialPosition: Duration = 15.seconds,
    duration: Duration = 30.seconds,
    step: Duration = 1.seconds,
    scrubEnabled: Boolean = true,
    onBegin: () -> Unit = {},
    onEnd: () -> Unit = {},
  ): TimelineState {
    val state = rememberTimelineState(duration, VIEWPORT_WIDTH_PX, TILE_WIDTH_PX, zoom = TimelineZoom.of(3))
    var position by remember { mutableStateOf(initialPosition) }
    val scrub: ScrubState? =
      if (scrubEnabled) {
        rememberScrubState(
          onSeek = { seeked ->
            position = seeked
            seeks += seeked
          },
          onBegin = onBegin,
          onEnd = onEnd,
        )
      } else {
        null
      }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
      Modifier
        .size(width = 400.dp, height = 48.dp)
        .focusRequester(focusRequester)
        .timelineKeys(state, scrub, position = { position }, step = step),
    )

    return state
  }

  private companion object {
    const val VIEWPORT_WIDTH_PX = 400f
    const val TILE_WIDTH_PX = 46
  }
}
