package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.ComposedOverlayState
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.boundsOn
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class ComposedOverlayBakeTest {
  @Test
  fun anElementThatIsNotShownIsBakedLikeOneThatIs(): TestResult =
    bakesLikeAPlainElement { ComposedOverlay(AT, onTransformChange = { }, shown = false) { Plate() } }

  @Test
  fun theSelectionAndTheHandlesAreNeverBaked(): TestResult =
    bakesLikeAPlainElement {
      ComposedOverlay(
        transform = AT,
        onTransformChange = { },
        selected = true,
        handles = ComposedOverlayHandles.Always,
      ) { Plate() }
    }

  @Test
  fun whatTheModifierDrawsIsNeverBaked(): TestResult =
    bakesLikeAPlainElement {
      val washed =
        Modifier.drawWithContent {
          drawContent()
          drawRect(Color.Green)
        }
      ComposedOverlay(AT, onTransformChange = { }, modifier = washed) { Plate() }
    }

  @Test
  fun anElementThatIsNotBakedIsLeftOut(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val overlays = ComposedOverlayState()
        setContent {
          Stage(overlays) {
            ComposedOverlay(AT, onTransformChange = { }) { Plate() }
            ComposedOverlay(ELSEWHERE, onTransformChange = { }, baked = false) { Plate() }
          }
        }
        waitForIdle()

        val baked = pumped { overlays.bake(OUTPUT) }

        baked.map { it.position } shouldContainExactly listOf(positionOf(AT))
      }
    }

  @Test
  fun theBakeCarriesTheElementsOpacityWindowAndAnimation(): TestResult =
    runComposeUiTest {
      coroutineScope {
        val overlays = ComposedOverlayState()
        val window = TimeRange.of(1.seconds, 3.seconds)
        val animation = OverlayAnimation { OverlayFrame.Identity }
        setContent {
          Stage(overlays) {
            ComposedOverlay(
              transform = AT,
              onTransformChange = { },
              opacity = 0.4f,
              visibleDuring = window,
              animation = animation,
            ) { Plate() }
          }
        }
        waitForIdle()

        val overlay = pumped { overlays.bake(OUTPUT) }.single()

        overlay.position shouldBe positionOf(AT)
        overlay.opacity shouldBe 0.4f
        overlay.visibleDuring shouldBe window
        overlay.animation shouldBeSameInstanceAs animation
      }
    }

  // Bakes a plain element under [other] at the same transform, and both must come out the same picture.
  private fun bakesLikeAPlainElement(other: @Composable VideoStageScope.() -> Unit): TestResult =
    runComposeUiTest {
      coroutineScope {
        val overlays = ComposedOverlayState()
        setContent {
          Stage(overlays) {
            ComposedOverlay(AT, onTransformChange = { }) { Plate() }
            other()
          }
        }
        waitForIdle()

        val baked = pumped { overlays.bake(OUTPUT) }

        baked shouldHaveSize 2
        baked.map { it.position } shouldContainExactly listOf(positionOf(AT), positionOf(AT))
        withClue("the two baked pictures") { baked[1].png().contentEquals(baked[0].png()) shouldBe true }
      }
    }

  @Composable
  private fun Stage(
    overlays: ComposedOverlayState,
    content: @Composable VideoStageScope.() -> Unit,
  ) {
    val size = with(LocalDensity.current) { Modifier.size(STAGE_WIDTH.toDp(), STAGE_HEIGHT.toDp()) }
    Box(size) {
      VideoStage(
        player = null,
        outputAspect = OUTPUT.width.toFloat() / OUTPUT.height,
        modifier = Modifier.fillMaxSize(),
        overlays = overlays,
      ) {
        content()
      }
    }
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

  private fun positionOf(transform: OverlayTransform): OverlayPosition =
    OverlayPosition.At(transform.boundsOn(PLATE_PX, ComposedOverlayState().canvas, OUTPUT).shouldNotBeNull().center)

  private companion object {
    val OUTPUT = Size(1280, 720)
    val AT = OverlayTransform(Anchor(0.31f, 0.62f), scale = 1.37f, rotationDegrees = 23f)
    val ELSEWHERE = OverlayTransform(Anchor(0.7f, 0.3f), scale = 0.8f, rotationDegrees = -12f)
    const val STAGE_WIDTH = 320
    const val STAGE_HEIGHT = 180

    // The plate is 200 by 60 dp at the layout density of 3.
    val PLATE_PX = IntSize(600, 180)
  }
}

@Composable
private fun Plate() {
  Box(Modifier.size(200.dp, 60.dp).background(Color.Red))
}
