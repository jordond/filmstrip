package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.drawnScale
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.compose.ui.component.ElementOnStage
import dev.jordond.filmstrip.compose.ui.component.HandlePoints
import dev.jordond.filmstrip.compose.ui.component.handlePoints
import dev.jordond.filmstrip.compose.ui.component.turnedBy
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class ComposedOverlayPointerTest {
  @Test
  fun aSizeHandleDragScalesByTheRatioOfThePointersDistanceFromTheCentre() =
    runComposeUiTest {
      val stage = staged()
      val grab = stage.points().size

      onNodeWithTag(STAGE).performMouseInput { drag(grab) { CENTRE + (grab - CENTRE) * (1f + 0.5f * it) } }

      stage.last shouldBe stage.element().moved(Offset.Zero, 1.5f, 0f).within(EPSILON)
    }

  @Test
  fun aSizeHandleDragPastTheLargestScaleHoldsThere() =
    runComposeUiTest {
      val start = OverlayTransform(START.center, scale = 8f)
      val stage = staged(start, plate = SMALL_PLATE)
      val grab = stage.points().size

      onNodeWithTag(STAGE).performMouseInput { drag(grab) { CENTRE + (grab - CENTRE) * (1f + it) } }

      stage.last.scale shouldBe ComposedOverlayDefaults.MAX_SCALE
    }

  @Test
  fun aTurnHandleDragTurnsByTheAngleThePointerGoesRoundTheCentre() =
    runComposeUiTest {
      val stage = staged()
      val grab = stage.points().turn

      onNodeWithTag(STAGE).performMouseInput { drag(grab) { CENTRE + (grab - CENTRE).turnedBy(SWEEP * it) } }

      val expected = stage.element().moved(Offset.Zero, 1f, SWEEP)
      stage.last.rotationDegrees shouldBe (expected.rotationDegrees plusOrMinus TURN_EPSILON)
      stage.last.scale shouldBe expected.scale
      stage.last.center shouldBe expected.center
    }

  @Test
  fun theHandlesOfATurnedElementAreNotWhereAnUprightOnesWouldBe() =
    runComposeUiTest {
      val stage = staged()
      val upright = ElementOnStage(OverlayTransform(START.center, START.scale), PICTURE, stage.element().drawnScale)
      val grab = upright.handlePoints(stage.laidOut, stage.stalkPx, rtl = false, STAGE_SIZE)!!.turn

      onNodeWithTag(STAGE).performMouseInput { drag(grab) { grab + Offset(STEP_PX * it, 0f) } }

      stage.all.shouldBeEmpty()
    }

  @Test
  fun theTurnHandleOfAnElementAtTheTopSitsBelowItAndStillTurns() =
    runComposeUiTest {
      val start = OverlayTransform(Anchor(0.5f, 0.1f), scale = START.scale)
      val stage = staged(start)
      val centre = stage.element().centre
      val grab = stage.points().turn

      withClue("above would be off the stage") { (grab.y > centre.y) shouldBe true }
      onNodeWithTag(STAGE).performMouseInput { drag(grab) { centre + (grab - centre).turnedBy(SWEEP * it) } }

      stage.last.rotationDegrees shouldBe (SWEEP plusOrMinus TURN_EPSILON)
    }

  @Test
  fun aHandleDragReportsTheGestureAroundItsMoves() =
    runComposeUiTest {
      val log = mutableListOf<String>()
      val stage = staged(onGesture = { log += "gesture $it" }, onMove = { log += "move" })
      val grab = stage.points().size

      onNodeWithTag(STAGE).performMouseInput { drag(grab) { grab + Offset(STEP_PX * it, STEP_PX * it) } }

      log.first() shouldBe "gesture true"
      log.last() shouldBe "gesture false"
      log.count { it.startsWith("gesture") } shouldBe 2
      withClue("moves fall between the two") { log.subList(1, log.size - 1).toSet() shouldBe setOf("move") }
    }

  @Test
  fun oneWheelNotchBackGrowsTheElementByTheWheelStep() =
    runComposeUiTest {
      val stage = staged()

      onNodeWithTag(STAGE).performMouseInput {
        moveTo(CENTRE)
        scroll(-1f, ScrollWheel.Vertical)
      }

      stage.all shouldBe listOf(stage.element().wheeled(-1f, turning = false))
      stage.last.scale shouldBe (START.scale * ComposedOverlayDefaults.WHEEL_STEP plusOrMinus EPSILON)
    }

  @Test
  fun aWheelNotchWithShiftHeldTurnsByTheTurnStep() =
    runComposeUiTest {
      val stage = staged()

      onNodeWithTag(STAGE).performKeyInput { keyDown(Key.ShiftLeft) }
      onNodeWithTag(STAGE).performMouseInput {
        moveTo(CENTRE)
        scroll(1f, ScrollWheel.Vertical)
      }
      onNodeWithTag(STAGE).performKeyInput { keyUp(Key.ShiftLeft) }

      stage.all shouldBe listOf(stage.element().wheeled(1f, turning = true))
      stage.last.rotationDegrees shouldBe
        (START.rotationDegrees + ComposedOverlayDefaults.TURN_STEP plusOrMinus EPSILON)
    }

  @Test
  fun aWheelIsTakenOnlyOverASelectedElement() =
    runComposeUiTest {
      val taken = mutableListOf<Boolean>()
      var selected by mutableStateOf(true)
      val stage = staged(selected = { selected }, onWheel = { taken += it })

      onNodeWithTag(STAGE).performMouseInput {
        moveTo(CENTRE)
        scroll(1f, ScrollWheel.Vertical)
        moveTo(Offset(CORNER_PX, CORNER_PX))
        scroll(1f, ScrollWheel.Vertical)
      }
      selected = false
      waitForIdle()
      onNodeWithTag(STAGE).performMouseInput {
        moveTo(CENTRE)
        scroll(1f, ScrollWheel.Vertical)
      }

      withClue("on the element, off it, on it unselected") { taken shouldBe listOf(true, false, false) }
      stage.all.size shouldBe 1
    }

  @Test
  fun autoHandlesAppearOnceAMouseIsSeenAndGoAgainOnATouch() =
    runComposeUiTest {
      val stage = staged(handles = ComposedOverlayHandles.Auto)
      val grab = stage.points().turn

      onNodeWithTag(STAGE).performTouchInput {
        down(CENTRE)
        up()
        down(grab)
        moveBy(Offset(STEP_PX, 0f))
        up()
      }
      withClue("touch only, no handle to grab") { stage.all.shouldBeEmpty() }

      onNodeWithTag(STAGE).performMouseInput { moveTo(CENTRE) }
      waitForIdle()
      onNodeWithTag(STAGE).performMouseInput { drag(grab) { grab + Offset(STEP_PX * it, 0f) } }
      withClue("a mouse crossed the element") { stage.all.shouldNotBeEmpty() }
      onNodeWithTag(STAGE).performMouseInput { exit() }

      onNodeWithTag(STAGE).performTouchInput {
        down(CENTRE)
        up()
      }
      waitForIdle()
      val before = stage.all.size
      val again = stage.points(stage.last).turn
      onNodeWithTag(STAGE).performTouchInput {
        down(again)
        moveBy(Offset(STEP_PX, 0f))
        up()
      }
      withClue("a finger touched the element") { stage.all.size shouldBe before }
    }

  @Test
  fun neverShowsNoHandleToAMouse() =
    runComposeUiTest {
      val stage = staged(handles = ComposedOverlayHandles.Never)
      val grab = stage.points().turn

      onNodeWithTag(STAGE).performMouseInput {
        moveTo(CENTRE)
        drag(grab) { grab + Offset(STEP_PX * it, 0f) }
      }

      stage.all.shouldBeEmpty()
    }

  private fun androidx.compose.ui.test.MouseInjectionScope.drag(
    from: Offset,
    at: (Float) -> Offset,
  ) {
    moveTo(from)
    press()
    for (step in 1..STEPS) moveTo(at(step.toFloat() / STEPS))
    release()
  }

  private fun ComposeUiTest.staged(
    start: OverlayTransform = START,
    plate: Pair<Dp, Dp> = PLATE,
    handles: ComposedOverlayHandles = ComposedOverlayHandles.Always,
    selected: () -> Boolean = { true },
    onGesture: (Boolean) -> Unit = { },
    onMove: () -> Unit = { },
    onWheel: (Boolean) -> Unit = { },
  ): Staged {
    val staged = Staged(start, plate)
    setContent {
      var transform by remember { mutableStateOf(start) }
      val density = LocalDensity.current
      staged.stalkPx = with(density) { OverlayHandleDefaults.StalkLength.toPx() }
      // A parent sees a wheel after the element has had it, so it reads whether the element took it.
      val wheel =
        Modifier.pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              if (event.type == PointerEventType.Scroll) onWheel(event.changes.first().isConsumed)
            }
          }
        }
      Box(with(density) { Modifier.size(STAGE_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }.testTag(STAGE).then(wheel)) {
        VideoStage(player = null, outputAspect = OUTPUT.width.toFloat() / OUTPUT.height, Modifier.fillMaxSize()) {
          ComposedOverlay(
            transform = transform,
            canvas = CANVAS,
            output = OUTPUT,
            onTransformChange = {
              staged.all += it
              transform = it
              onMove()
            },
            selected = selected(),
            onGestureChange = onGesture,
            handles = handles,
          ) {
            Box(Modifier.size(plate.first, plate.second).background(Color.Red))
          }
        }
      }
    }
    waitForIdle()
    return staged
  }

  private fun OverlayTransform.within(epsilon: Float): io.kotest.matchers.Matcher<OverlayTransform> =
    io.kotest.matchers.Matcher { actual ->
      val close =
        kotlin.math.abs(actual.scale - scale) <= epsilon &&
          kotlin.math.abs(actual.rotationDegrees - rotationDegrees) <= epsilon &&
          kotlin.math.abs(actual.center.x - center.x) <= epsilon &&
          kotlin.math.abs(actual.center.y - center.y) <= epsilon
      io.kotest.matchers.MatcherResult(close, { "$actual should be within $epsilon of $this" }, { "$actual" })
    }

  private class Staged(
    val start: OverlayTransform,
    plate: Pair<Dp, Dp>,
  ) {
    val all = mutableListOf<OverlayTransform>()
    val last: OverlayTransform get() = all.last()
    var stalkPx = 0f

    // The plate is laid out at the layout density of 3.
    val laidOut = IntSize((plate.first.value * LAYOUT_DENSITY).toInt(), (plate.second.value * LAYOUT_DENSITY).toInt())

    fun element(transform: OverlayTransform = start): ElementOnStage =
      ElementOnStage(transform, PICTURE, transform.drawnScale(CANVAS, STAGE_WIDTH.toFloat()))

    fun points(transform: OverlayTransform = start): HandlePoints =
      element(transform).handlePoints(laidOut, stalkPx, rtl = false, STAGE_SIZE)!!
  }

  private companion object {
    val OUTPUT = Size(1280, 720)
    val CANVAS = OverlayCanvas(360.dp)
    val START = OverlayTransform(Anchor(0.5f, 0.5f), scale = 0.75f, rotationDegrees = 30f)
    val PLATE = 200.dp to 60.dp
    val SMALL_PLATE = 10.dp to 10.dp
    const val STAGE = "stage"
    const val STAGE_WIDTH = 320
    const val STAGE_HEIGHT = 180
    val PICTURE = Rect(0f, 0f, STAGE_WIDTH.toFloat(), STAGE_HEIGHT.toFloat())
    val STAGE_SIZE =
      androidx.compose.ui.geometry
        .Size(STAGE_WIDTH.toFloat(), STAGE_HEIGHT.toFloat())
    val CENTRE = Offset(STAGE_WIDTH * 0.5f, STAGE_HEIGHT * 0.5f)
    const val LAYOUT_DENSITY = 3f
    const val SWEEP = 40f
    const val STEPS = 10
    const val STEP_PX = 4f
    const val CORNER_PX = 4f
    const val EPSILON = 1e-3f
    const val TURN_EPSILON = 0.05f
  }
}
