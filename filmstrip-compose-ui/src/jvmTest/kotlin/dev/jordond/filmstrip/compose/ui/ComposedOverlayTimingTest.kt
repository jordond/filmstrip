package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.ComposedOverlayState
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.drawnScale
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.drawnRangeOf
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.overlayFrameWithin
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A stage with a player draws a composed overlay at the playhead with the frame the export's sampler answers.
 *
 * Every expected value comes from `overlayFrameWithin`, read midway through an entrance where the opacity, the offset
 * and the scale are all partway.
 */
@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class ComposedOverlayTimingTest {
  @Test
  fun midwayThroughAnEntranceTheElementIsDrawnWithTheSamplersFrame(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = MID_ENTRANCE)
      setContent {
        TimedStage(engine) { ComposedOverlay(AT, { }, visibleDuring = WINDOW, animation = ENTRANCE) { Plate() } }
      }
      settle()

      val frame = frameAt(MID_ENTRANCE).shouldNotBeNull()
      withClue("the sampled frame is partway on every property: $frame") {
        (frame.opacity > 0f && frame.opacity < 1f) shouldBe true
        (frame.scale > 0f && frame.scale < 1f) shouldBe true
        (frame.offset.x < 0f && frame.offset.y > 0f) shouldBe true
      }
      val stage = stagePixels()
      val centre = CENTRE + Offset(frame.offset.x * STAGE_WIDTH, frame.offset.y * STAGE_HEIGHT)
      val half = PLATE_WIDTH_PX * AT.drawnScale(CANVAS, STAGE_WIDTH.toFloat()) * frame.scale / 2f
      val faded = Color(frame.opacity, 0f, 0f)
      stage.at(centre).shouldBeNear(faded, "the moved centre")
      stage.at(centre + Offset(half - EDGE_PX, 0f)).shouldBeNear(faded, "inside the scaled end")
      stage.at(centre + Offset(half + EDGE_PX, 0f)).shouldBeNear(Color.Black, "past the scaled end")
      stage.at(centre - Offset(half - EDGE_PX, 0f)).shouldBeNear(faded, "inside the scaled start")
      stage.at(centre - Offset(half + EDGE_PX, 0f)).shouldBeNear(Color.Black, "before the scaled start")
    }

  @Test
  fun theElementsOwnOpacityIsFoldedIntoTheFrameOnce(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = MID_ENTRANCE)
      setContent {
        TimedStage(engine) {
          ComposedOverlay(AT, { }, opacity = OPACITY, visibleDuring = WINDOW, animation = FADE) { Plate() }
        }
      }
      settle()

      val frame = overlayFrameWithin(WINDOW, FADE, OPACITY, drawnRangeOf(WINDOW, CLOCK_SPAN), MID_ENTRANCE)
      stagePixels().at(CENTRE).shouldBeNear(Color(frame.shouldNotBeNull().opacity, 0f, 0f), "the faded plate")
    }

  @Test
  fun anElementThatIsNeverBakedFollowsThePlayheadToo(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = MID_ENTRANCE)
      setContent {
        TimedStage(engine) {
          ComposedOverlay(AT, { }, baked = false, visibleDuring = WINDOW, animation = FADE) { Plate() }
        }
      }
      settle()

      val frame = overlayFrameWithin(WINDOW, FADE, 1f, drawnRangeOf(WINDOW, CLOCK_SPAN), MID_ENTRANCE)
      stagePixels().at(CENTRE).shouldBeNear(Color(frame.shouldNotBeNull().opacity, 0f, 0f), "the faded plate")

      engine.moveTo(BEFORE_WINDOW)
      settle()
      stagePixels().at(CENTRE).shouldBeNear(Color.Black, "before the window")
    }

  @Test
  fun outsideItsWindowTheElementDrawsNothingAndATouchReachesTheOneBeneath(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = BEFORE_WINDOW)
      var beneath = 0
      var above = 0
      setContent {
        TimedStage(engine) {
          ComposedOverlay(AT, { }, onSelect = { beneath++ }) { Plate(Color.Blue) }
          ComposedOverlay(AT, { }, visibleDuring = WINDOW, onSelect = { above++ }) { Plate() }
        }
      }
      settle()

      frameAt(BEFORE_WINDOW).shouldBeNull()
      stagePixels().at(CENTRE).shouldBeNear(Color.Blue, "the element beneath")
      touch(CENTRE)
      (beneath to above) shouldBe (1 to 0)

      engine.moveTo(HELD)
      settle()

      stagePixels().at(CENTRE).shouldBeNear(Color.Red, "the element above, inside its window")
      touch(CENTRE)
      (beneath to above) shouldBe (1 to 1)
    }

  @Test
  fun aStillElementIsDrawnAsAuthoredOutsideItsWindowAndMidEntrance(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = BEFORE_WINDOW)
      setContent {
        TimedStage(engine) {
          ComposedOverlay(AT, { }, visibleDuring = WINDOW, animation = ENTRANCE, still = true) { Plate() }
        }
      }
      settle()
      stagePixels().at(CENTRE).shouldBeNear(Color.Red, "before the window")

      engine.moveTo(MID_ENTRANCE)
      settle()
      stagePixels().at(CENTRE).shouldBeNear(Color.Red, "midway through the entrance")
    }

  @Test
  fun anElementThatIsNotShownStaysHiddenWhenHeldStill(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = HELD)
      setContent {
        TimedStage(engine) { ComposedOverlay(AT, { }, shown = false, visibleDuring = WINDOW, still = true) { Plate() } }
      }
      settle()

      stagePixels().at(CENTRE).shouldBeNear(Color.Black, "the hidden element")
    }

  @Test
  fun aSelectedElementIsHeldStillUnlessToldOtherwise(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = BEFORE_WINDOW)
      var still by mutableStateOf<Boolean?>(null)
      setContent {
        TimedStage(engine) {
          val held = still
          if (held == null) {
            ComposedOverlay(SMALL, { }, visibleDuring = WINDOW, selected = true) { Plate() }
          } else {
            ComposedOverlay(SMALL, { }, visibleDuring = WINDOW, selected = true, still = held) { Plate() }
          }
        }
      }
      settle()
      stagePixels().at(CENTRE).red shouldBe 1f

      still = false
      settle()
      stagePixels().at(CENTRE).shouldBeNear(Color.Black, "selected, not held, outside its window")
    }

  @Test
  fun aStageWithNoPlayerDrawsEveryElementStill(): TestResult =
    runComposeUiTest {
      setContent {
        TimedStage(null) { ComposedOverlay(AT, { }, visibleDuring = WINDOW, animation = ENTRANCE) { Plate() } }
      }
      settle()

      stagePixels().at(CENTRE).shouldBeNear(Color.Red, "the element with no player")
    }

  @Test
  fun aPlayerWithNoDurationDrawsEveryElementStill(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(length = null, position = BEFORE_WINDOW)
      setContent {
        TimedStage(engine) { ComposedOverlay(AT, { }, visibleDuring = WINDOW, animation = ENTRANCE) { Plate() } }
      }
      settle()

      stagePixels().at(CENTRE).shouldBeNear(Color.Red, "the element with no duration")
    }

  @Test
  fun aPlayerParkedAtTheEndStillDrawsAnElementWhoseWindowRunsToTheEnd(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = CLOCK_LENGTH)
      val toTheEnd = TimeRange.of(6.seconds, CLOCK_LENGTH)
      setContent { TimedStage(engine) { ComposedOverlay(AT, { }, visibleDuring = toTheEnd) { Plate() } } }
      settle()

      overlayFrameWithin(toTheEnd, null, 1f, drawnRangeOf(toTheEnd, CLOCK_SPAN), CLOCK_LENGTH).shouldBeNull()
      stagePixels().at(CENTRE).shouldBeNear(Color.Red, "the element on the last frame")
    }

  @Test
  fun aTouchMidwayThroughASlideLandsOnTheAuthoredBox(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = MID_ENTRANCE)
      val slide = slideIn(OverlayOffset(-0.6f, 0f), ENTRANCE_LENGTH)
      var selected = 0
      setContent {
        TimedStage(engine) {
          ComposedOverlay(SMALL, { }, visibleDuring = WINDOW, animation = slide, onSelect = { selected++ }) { Plate() }
        }
      }
      settle()

      val frame =
        overlayFrameWithin(
          WINDOW,
          slide,
          1f,
          drawnRangeOf(WINDOW, CLOCK_SPAN),
          MID_ENTRANCE,
        ).shouldNotBeNull()
      val drawnAt = CENTRE + Offset(frame.offset.x * STAGE_WIDTH, 0f)
      val stage = stagePixels()
      stage.at(drawnAt).shouldBeNear(Color.Red, "where the slide draws the plate")
      stage.at(CENTRE).shouldBeNear(Color.Black, "the authored box, which the plate has not reached")

      touch(drawnAt)
      selected shouldBe 0
      touch(CENTRE)
      selected shouldBe 1
    }

  @Test
  fun theContentComposesOnceWhileThePlayheadMoves(): TestResult =
    runComposeUiTest {
      val engine = ClockEngine(position = WINDOW.start)
      var composed = 0
      setContent {
        TimedStage(engine) {
          ComposedOverlay(AT, { }, visibleDuring = WINDOW, animation = ENTRANCE) {
            SideEffect { composed++ }
            Plate()
          }
        }
      }
      settle()
      val first = stagePixels().at(CENTRE)

      repeat(TICKS) { tick ->
        engine.moveTo(WINDOW.start + TICK * (tick + 1))
        settle()
      }

      composed shouldBe 1
      withClue("the stage redrew as the playhead moved") { (stagePixels().at(CENTRE) == first) shouldBe false }
    }

  @Test
  fun aBakeTakenMidFadeIsTheStillPictureAndCarriesTheAnimation(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val engine = ClockEngine(position = MID_ENTRANCE)
        val overlays = ComposedOverlayState(CANVAS)
        var still by mutableStateOf(false)
        setContent {
          TimedStage(engine, overlays) {
            ComposedOverlay(AT, { }, visibleDuring = WINDOW, animation = ENTRANCE, still = still) { Plate() }
          }
        }
        settle()
        val midFade = pumped { overlays.bake(OUTPUT) }.single()
        still = true
        settle()
        val held = pumped { overlays.bake(OUTPUT) }.single()

        midFade.animation shouldBeSameInstanceAs ENTRANCE
        midFade.visibleDuring shouldBe WINDOW
        withClue("the two baked pictures") { midFade.png().contentEquals(held.png()) shouldBe true }
      }
    }

  private fun frameAt(time: Duration): OverlayFrame? =
    overlayFrameWithin(WINDOW, ENTRANCE, 1f, drawnRangeOf(WINDOW, CLOCK_SPAN), time)

  @Composable
  private fun TimedStage(
    engine: ClockEngine?,
    overlays: ComposedOverlayState = ComposedOverlayState(CANVAS),
    content: @Composable VideoStageScope.() -> Unit,
  ) {
    val size = with(LocalDensity.current) { Modifier.size(STAGE_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
    Box(size.testTag(STAGE).background(Color.Black)) {
      VideoStage(
        player = engine?.let { rememberClockPlayer(it) },
        outputAspect = OUTPUT.width.toFloat() / OUTPUT.height,
        modifier = Modifier.fillMaxSize(),
        overlays = remember { overlays },
      ) {
        content()
      }
    }
  }

  private fun ComposeUiTest.settle() {
    waitForIdle()
    mainClock.advanceTimeByFrame()
    waitForIdle()
  }

  private fun ComposeUiTest.stagePixels(): PixelMap = onNodeWithTag(STAGE).captureToImage().toPixelMap()

  private fun ComposeUiTest.touch(point: Offset) {
    onNodeWithTag(STAGE).performTouchInput { click(point) }
    waitForIdle()
  }

  // Frames only come when the test clock moves, and the encode runs on a real thread, so this steps both.
  private suspend fun <T> ComposeUiTest.pumped(work: suspend () -> T): T =
    coroutineScope {
      val running = async { work() }
      while (!running.isCompleted) {
        mainClock.advanceTimeByFrame()
        awaitIdle()
        withContext(Dispatchers.Default) { delay(1) }
      }
      running.await()
    }

  private fun ImageOverlay.png(): ByteArray = image.shouldBeInstanceOf<ImageSource.Bytes>().bytes

  private fun PixelMap.at(point: Offset): Color = this[point.x.roundToInt(), point.y.roundToInt()]

  private fun Color.shouldBeNear(
    expected: Color,
    name: String,
  ) {
    val distance = maxOf(abs(red - expected.red), abs(green - expected.green), abs(blue - expected.blue))
    withClue("$name read $this, expected $expected") { (distance < NEAR) shouldBe true }
  }

  private companion object {
    val OUTPUT = Size(640, 360)
    val CANVAS = OverlayCanvas(360.dp)
    val AT = OverlayTransform(Anchor(0.5f, 0.5f))
    val SMALL = OverlayTransform(Anchor(0.5f, 0.5f), scale = 0.5f)
    val WINDOW = TimeRange.of(1.seconds, 4.seconds)
    val ENTRANCE_LENGTH = 400.milliseconds
    val MID_ENTRANCE = 1200.milliseconds
    val BEFORE_WINDOW = 500.milliseconds
    val HELD = 2.seconds
    val TICK = 33.milliseconds
    const val TICKS = 10
    const val OPACITY = 0.6f
    val FADE = fade(inDuration = ENTRANCE_LENGTH)

    // Grows from nothing over the entrance, which no built-in animation does.
    val GROW =
      OverlayAnimation { time ->
        OverlayFrame(scale = (time.elapsed / ENTRANCE_LENGTH).coerceAtMost(1.0).toFloat())
      }
    val ENTRANCE = FADE + slideIn(OverlayOffset(-0.2f, 0.1f), ENTRANCE_LENGTH) + GROW

    const val STAGE = "stage"
    const val STAGE_WIDTH = 320
    const val STAGE_HEIGHT = 180
    val CENTRE = Offset(STAGE_WIDTH / 2f, STAGE_HEIGHT / 2f)

    // The plate is 200 by 60 dp at the layout density of 3.
    const val PLATE_WIDTH_PX = 600f
    const val EDGE_PX = 2.5f

    // Room for the sRGB round trip of a capture and for the rounding of a blend at part opacity.
    const val NEAR = 4f / 255f
  }
}

@Composable
private fun Plate(color: Color = Color.Red) {
  Box(Modifier.size(200.dp, 60.dp).background(color))
}
