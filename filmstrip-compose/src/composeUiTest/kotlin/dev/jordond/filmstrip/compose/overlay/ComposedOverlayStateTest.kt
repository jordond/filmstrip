package dev.jordond.filmstrip.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class ComposedOverlayStateTest {
  @Test
  fun aBakeEqualsACaptureOfTheSameContent(): TestResult = bakesLikeACapture(MID)

  @Test
  fun aBakeOfAnElementHangingOffTheFrameEqualsItsCapture(): TestResult = bakesLikeACapture(EDGE)

  @Test
  fun anElementTheStageDoesNotShowIsBaked(): TestResult = bakesLikeACapture(MID, shown = false)

  @Test
  fun theBakeFollowsTheOrderTheStageDraws(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        setContent {
          TestStage(state) {
            TestElement(state, OUTPUT, at(LOW)) { Plate(Color.Red) }
            TestElement(state, OUTPUT, at(RAISED), Modifier.zIndex(1f)) { Plate(Color.Green) }
            TestElement(state, OUTPUT, at(HIGH)) { Plate(Color.Blue) }
          }
        }
        awaitIdle()

        val baked = pumped(async { state.bake(OUTPUT) })

        baked.map { it.position } shouldContainExactly listOf(LOW, HIGH, RAISED).map { positionOf(at(it)) }
      }
    }

  @Test
  fun anElementOffTheFrameIsLeftOut(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        setContent {
          TestStage(state) {
            TestElement(state, OUTPUT, OverlayTransform(Anchor(3f, 0.5f))) { Plate(Color.Red) }
            TestElement(state, OUTPUT, MID) { Plate(Color.Blue) }
          }
        }
        awaitIdle()

        val baked = pumped(async { state.bake(OUTPUT) })

        baked.map { it.position } shouldContainExactly listOf(positionOf(MID))
      }
    }

  @Test
  fun theOverlayCarriesTheElementsOpacityWindowAndAnimation(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        val window = TimeRange.of(1.seconds, 3.seconds)
        val animation = OverlayAnimation { OverlayFrame.Identity }
        setContent {
          TestStage(state) {
            TestElement(
              state,
              OUTPUT,
              MID,
              opacity = 0.4f,
              visibleDuring = window,
              animation = animation,
            ) { Plate(Color.Red) }
          }
        }
        awaitIdle()

        val overlay = pumped(async { state.bake(OUTPUT) }).single()

        overlay.opacity shouldBe 0.4f
        overlay.visibleDuring shouldBe window
        overlay.animation shouldBeSameInstanceAs animation
      }
    }

  @Test
  fun contentChangedJustBeforeTheBakeIsBakedAsChanged(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        val capturer = OverlayCapturer()
        var color by mutableStateOf(Color.Red)
        var transform by mutableStateOf(EDGE)
        setContent {
          OverlayCaptureHost(capturer)
          TestStage(state) { TestElement(state, OUTPUT, transform) { Plate(color) } }
        }
        awaitIdle()

        color = Color.Blue
        transform = MID
        val baked = pumped(async { state.bake(OUTPUT) })
        val captured = pumped(async { capturer.capture(MID, CANVAS, OUTPUT) { Plate(Color.Blue) } })

        baked.single() shouldMatch captured.shouldNotBeNull()
      }
    }

  @Test
  fun aStateWithNoStageRefusesToBake(): TestResult =
    runComposeUiTest {
      setContent { }
      val failure = runCatching { ComposedOverlayState().bake(OUTPUT) }

      failure.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>().message shouldBe NO_STAGE
    }

  @Test
  fun aStageThatLeavesMidBakeFailsTheBake(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        var mounted by mutableStateOf(true)
        setContent { if (mounted) TestStage(state) { TestElement(state, OUTPUT, MID) { Plate(Color.Red) } } }
        awaitIdle()

        val work = async { runCatching { state.bake(OUTPUT) } }
        // Lets the bake put its request up before the stage goes.
        yield()
        withClue("the bake is waiting on the stage") { state.request.shouldNotBeNull() }
        mounted = false
        repeat(LATE_FRAMES) { pumpOnce() }

        withClue("the bake in hand") { work.isCompleted shouldBe true }
        work
          .await()
          .exceptionOrNull()
          .shouldBeInstanceOf<IllegalStateException>()
          .message shouldBe STAGE_LEFT
      }
    }

  @Test
  fun aStageThatIsNeverDrawnFailsTheBake(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        setContent {
          TestStage(state) {
            // Measured and never placed, which is how an element sits on a stage that is not drawn.
            Layout(content = { TestElement(state, OUTPUT, MID) { Plate(Color.Red) } }) { list, constraints ->
              list.forEach { it.measure(constraints) }
              layout(0, 0) { }
            }
          }
        }
        awaitIdle()

        val failure = pumped(async { runCatching { state.bake(OUTPUT) } })

        failure.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>().message shouldBe STAGE_NOT_DRAWN
      }
    }

  @Test
  fun aSecondStageOnTheSameStateIsRefused(): TestResult =
    runComposeUiTest {
      val state = ComposedOverlayState(CANVAS)

      val failure =
        runCatching {
          setContent {
            TestStage(state) { }
            TestStage(state) { }
          }
          awaitIdle()
        }

      failure.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>().message shouldBe SECOND_STAGE
    }

  @Test
  fun aCancelledBakeLeavesTheStageFreeForTheNext(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = staged { TestElement(it, OUTPUT, MID) { Plate(Color.Red) } }

        val cancelled = async { state.bake(OUTPUT) }
        yield()
        cancelled.cancel()
        val next = pumped(async { state.bake(OUTPUT) })

        cancelled.isCancelled shouldBe true
        next.map { it.position } shouldContainExactly listOf(positionOf(MID))
      }
    }

  @Test
  fun twoBakesRunOneAfterTheOther(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = staged { TestElement(it, OUTPUT, MID) { Plate(Color.Red) } }

        val first = async { state.bake(OUTPUT) }
        val second = async { state.bake(OUTPUT) }
        yield()
        val inHand = state.request.shouldNotBeNull()
        repeat(LATE_FRAMES) { pumpOnce() }
        withClue("the second bake starts only once the first is done") {
          first.isCompleted shouldBe (state.request !== inHand)
        }
        pumped(first)
        pumped(second)

        first.await().single() shouldMatch second.await().single()
      }
    }

  @Test
  fun aStageWithNoElementsBakesNothing(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = staged { }

        pumped(async { state.bake(OUTPUT) }).shouldBeEmpty()
      }
    }

  @Test
  fun aStateHoldsNothingOnceItsStageLeaves(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        var mounted by mutableStateOf(true)
        setContent { if (mounted) TestStage(state) { TestElement(state, OUTPUT, MID) { Plate(Color.Red) } } }
        awaitIdle()
        pumped(async { state.bake(OUTPUT) }).size shouldBe 1

        mounted = false
        awaitIdle()

        state.recordings.shouldBeEmpty()
        state.request.shouldBeNull()
        state.hosts shouldBe 0
      }
    }

  private fun bakesLikeACapture(
    transform: OverlayTransform,
    shown: Boolean = true,
  ): TestResult =
    runComposeUiTest {
      coroutineScope {
        val state = ComposedOverlayState(CANVAS)
        val capturer = OverlayCapturer()
        setContent {
          OverlayCaptureHost(capturer)
          TestStage(state) { TestElement(state, OUTPUT, transform, shown = shown) { Plate(HALF_RED) } }
        }
        awaitIdle()

        val baked = pumped(async { state.bake(OUTPUT) })
        val captured = pumped(async { capturer.capture(transform, CANVAS, OUTPUT) { Plate(HALF_RED) } })

        baked.single() shouldMatch captured.shouldNotBeNull()
      }
    }

  private suspend fun ComposeUiTest.staged(content: @Composable (ComposedOverlayState) -> Unit): ComposedOverlayState {
    val state = ComposedOverlayState(CANVAS)
    setContent { TestStage(state) { content(state) } }
    awaitIdle()
    return state
  }

  private infix fun ImageOverlay.shouldMatch(other: ImageOverlay) {
    position shouldBe other.position
    scale shouldBe other.scale
    withClue("the baked picture") { png().contentEquals(other.png()) shouldBe true }
  }

  private fun ImageOverlay.png(): ByteArray = image.shouldBeInstanceOf<ImageSource.Bytes>().bytes

  private fun at(center: Anchor): OverlayTransform = OverlayTransform(center, MID.scale, MID.rotationDegrees)

  private fun positionOf(transform: OverlayTransform): OverlayPosition =
    OverlayPosition.At(transform.boundsOn(PLATE_PX, CANVAS, OUTPUT).shouldNotBeNull().center)

  private companion object {
    val OUTPUT = Size(640, 360)
    val CANVAS = OverlayCanvas(360.dp)
    val MID = OverlayTransform(Anchor(0.31f, 0.62f), scale = 1.37f, rotationDegrees = 23f)
    val EDGE = OverlayTransform(Anchor(0.02f, 0.5f), scale = 1.37f, rotationDegrees = 23f)
    val LOW = Anchor(0.3f, 0.3f)
    val RAISED = Anchor(0.5f, 0.5f)
    val HIGH = Anchor(0.7f, 0.7f)
    val HALF_RED = Color(0x80FF0000)
    val PLATE_PX = IntSize(600, 180)
    const val LATE_FRAMES = 6
  }
}
