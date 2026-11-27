package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.drawnScale
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class ComposedOverlayGestureTest {
  @Test
  fun aDragMovesTheCentreByTheShareOfTheFrameItCovers() =
    runComposeUiTest {
      val moves = staged()

      onNodeWithTag(STAGE).performTouchInput {
        down(CENTRE)
        repeat(STEPS) { moveBy(Offset(STAGE_WIDTH * 0.1f / STEPS, 0f)) }
        up()
      }

      moves.last.center.x shouldBe (START.center.x + 0.1f plusOrMinus EPSILON)
      moves.last.center.y shouldBe (START.center.y plusOrMinus EPSILON)
      moves.last.scale shouldBe START.scale
      moves.last.rotationDegrees shouldBe START.rotationDegrees
    }

  @Test
  fun aDragPastTheEdgeHoldsTheCentreOnTheFrame() =
    runComposeUiTest {
      val moves = staged()

      onNodeWithTag(STAGE).performTouchInput {
        down(CENTRE)
        repeat(STEPS) { moveBy(Offset(STAGE_WIDTH.toFloat() / STEPS, 0f)) }
        up()
      }

      moves.last.center.x shouldBe 1f
    }

  @Test
  fun aPinchMultipliesTheScale() =
    runComposeUiTest {
      val moves = staged()

      onNodeWithTag(STAGE).performTouchInput { twoFingers(fromSpan = SPAN, toSpan = SPAN * 1.5f, turn = 0f) }

      moves.last.scale shouldBe (START.scale * 1.5f plusOrMinus EPSILON)
      moves.last.rotationDegrees shouldBe (START.rotationDegrees plusOrMinus EPSILON)
    }

  @Test
  fun aPinchStopsAtTheLargestScale() =
    runComposeUiTest {
      val moves = staged(OverlayTransform(START.center, scale = 8f, rotationDegrees = START.rotationDegrees))

      onNodeWithTag(STAGE).performTouchInput { twoFingers(fromSpan = SPAN, toSpan = SPAN * 1.5f, turn = 0f) }

      moves.last.scale shouldBe ComposedOverlayDefaults.MAX_SCALE
    }

  @Test
  fun aTwistAddsItsAngleToTheRotation() =
    runComposeUiTest {
      val moves = staged()

      onNodeWithTag(STAGE).performTouchInput { twoFingers(fromSpan = SPAN, toSpan = SPAN, turn = TWIST) }

      moves.last.rotationDegrees shouldBe (START.rotationDegrees + TWIST plusOrMinus TURN_EPSILON)
      moves.last.scale shouldBe (START.scale plusOrMinus EPSILON)
    }

  @Test
  fun aTouchInsideTheBoxButOffTheTurnedElementIsNotTaken() =
    runComposeUiTest {
      val moves = staged()
      // Just past the element's lower edge, which is still well inside its upright bounding box.
      val beside = CENTRE + turned(Offset(0f, plateHalfHeight() + BESIDE_PX), START.rotationDegrees)

      onNodeWithTag(STAGE).performTouchInput {
        down(beside)
        repeat(STEPS) { moveBy(Offset(STEP_PX, STEP_PX)) }
        up()
      }

      moves.all shouldBe emptyList()
    }

  @Test
  fun theTopmostElementTakesATouchAndOneOffBothPassesBeneath() =
    runComposeUiTest {
      var top by mutableStateOf<OverlayTransform?>(null)
      var bottom by mutableStateOf<OverlayTransform?>(null)
      var tapped by mutableStateOf(false)
      setContent {
        Stage {
          Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { tapped = true } })
          ComposedOverlay(START, CANVAS, OUTPUT, onTransformChange = { bottom = it }) { Plate() }
          ComposedOverlay(START, CANVAS, OUTPUT, onTransformChange = { top = it }) { Plate() }
        }
      }
      waitForIdle()

      onNodeWithTag(STAGE).performTouchInput {
        down(CENTRE)
        moveBy(Offset(STEP_PX, 0f))
        up()
      }
      onNodeWithTag(STAGE).performTouchInput { click(Offset(CORNER_PX, CORNER_PX)) }
      waitForIdle()

      withClue("the top element took the drag") { (top != null) shouldBe true }
      withClue("the element under it saw nothing") { bottom shouldBe null }
      withClue("the tap off both reached the layer beneath") { tapped shouldBe true }
    }

  @Test
  fun aTouchOnThePartClippedIntoTheLetterboxIsNotTaken() =
    runComposeUiTest {
      val moves = mutableListOf<OverlayTransform>()
      setContent {
        val size = with(LocalDensity.current) { Modifier.size(STAGE_WIDTH.toDp(), BANDED_HEIGHT.toDp()) }
        // The stage holds its box at the widest aspect it lays out to, so the wider picture is letterboxed inside it.
        Box(size.testTag(STAGE)) {
          VideoStage(player = null, outputAspect = BANDED.width.toFloat() / BANDED.height, Modifier.fillMaxSize()) {
            ComposedOverlay(START, CANVAS, BANDED, onTransformChange = { moves += it }) { Plate() }
          }
        }
      }
      waitForIdle()

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(STAGE_WIDTH / 2f, BAR_PX / 2f))
        moveBy(Offset(STEP_PX, 0f))
        up()
      }
      moves shouldBe emptyList()

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(STAGE_WIDTH / 2f, BANDED_HEIGHT / 2f))
        moveBy(Offset(STEP_PX, 0f))
        up()
      }
      (moves.isNotEmpty()) shouldBe true
    }

  private fun ComposeUiTest.staged(start: OverlayTransform = START): Moves {
    val moves = Moves()
    setContent {
      var transform by remember { mutableStateOf(start) }
      Stage {
        ComposedOverlay(
          transform = transform,
          canvas = CANVAS,
          output = OUTPUT,
          onTransformChange = {
            moves.all += it
            transform = it
          },
        ) { Plate() }
      }
    }
    waitForIdle()
    return moves
  }

  @Composable
  private fun Stage(content: @Composable VideoStageScope.() -> Unit) {
    val size = with(LocalDensity.current) { Modifier.size(STAGE_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
    Box(size.testTag(STAGE)) {
      VideoStage(player = null, outputAspect = OUTPUT.width.toFloat() / OUTPUT.height, Modifier.fillMaxSize()) {
        content()
      }
    }
  }

  // Two fingers either side of the centre, spreading from one span to another while turning together.
  private fun TouchInjectionScope.twoFingers(
    fromSpan: Float,
    toSpan: Float,
    turn: Float,
  ) {
    fun finger(
      side: Float,
      step: Int,
    ): Offset {
      val progress = step.toFloat() / STEPS
      val span = fromSpan + (toSpan - fromSpan) * progress
      return CENTRE + turned(Offset(side * span / 2f, 0f), turn * progress)
    }
    down(0, finger(-1f, 0))
    down(1, finger(1f, 0))
    for (step in 1..STEPS) {
      updatePointerTo(0, finger(-1f, step))
      updatePointerTo(1, finger(1f, step))
      move()
    }
    up(0)
    up(1)
  }

  private fun plateHalfHeight(): Float = PLATE_HEIGHT_PX / 2f * START.drawnScale(CANVAS, STAGE_WIDTH.toFloat())

  private fun turned(
    offset: Offset,
    degrees: Float,
  ): Offset {
    val radians = degrees * PI / DEGREES_HALF_TURN
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    return Offset(offset.x * cos - offset.y * sin, offset.x * sin + offset.y * cos)
  }

  private class Moves {
    val all = mutableListOf<OverlayTransform>()
    val last: OverlayTransform get() = all.last()
  }

  private companion object {
    val OUTPUT = Size(1280, 720)
    val CANVAS = OverlayCanvas(360.dp)
    val START = OverlayTransform(Anchor(0.5f, 0.5f), scale = 1.5f, rotationDegrees = 30f)
    const val STAGE = "stage"
    const val STAGE_WIDTH = 320
    const val STAGE_HEIGHT = 180

    // An 8:1 picture in a 5:1 box, 320 by 40 between two 12 pixel bars.
    val BANDED = Size(1600, 200)
    const val BANDED_HEIGHT = 64
    const val BAR_PX = 12f
    val CENTRE = Offset(STAGE_WIDTH * 0.5f, STAGE_HEIGHT * 0.5f)

    // The plate is 60 dp tall at the layout density of 3.
    const val PLATE_HEIGHT_PX = 180f
    const val BESIDE_PX = 6f
    const val SPAN = 40f
    const val TWIST = 30f
    const val STEPS = 10
    const val STEP_PX = 4f
    const val CORNER_PX = 4f
    const val EPSILON = 1e-3f
    const val TURN_EPSILON = 0.05f
    const val DEGREES_HALF_TURN = 180.0
  }
}

@Composable
private fun Plate() {
  Box(Modifier.size(200.dp, 60.dp).background(Color.Red))
}
