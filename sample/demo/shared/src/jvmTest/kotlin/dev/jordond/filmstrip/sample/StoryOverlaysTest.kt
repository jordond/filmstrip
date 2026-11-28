package dev.jordond.filmstrip.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.overlay.OVERLAY_LAYOUT_DENSITY
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.boundsOn
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.sample.ui.screen.StoryElements
import dev.jordond.filmstrip.sample.ui.screen.family
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, InternalFilmstripApi::class)
class StoryOverlaysTest {
  @Test
  fun addingAnElementSelectsIt() {
    val stories = StoryOverlays()

    stories.addCaption("Day one")
    stories.addSticker(StorySticker.Heart)

    stories.elements shouldHaveSize 2
    stories.selected.shouldNotBeNull().content shouldBe StoryContent.Sticker(StorySticker.Heart)
  }

  @Test
  fun aTransformLandsOnTheElementItWasFor() {
    val stories = StoryOverlays()
    stories.addCaption()
    stories.addSticker(StorySticker.Star)
    val moved = OverlayTransform(Anchor(0.2f, 0.7f), scale = 2f, rotationDegrees = 45f)

    stories.update(stories.elements.first().id) { it.copy(transform = moved) }

    stories.elements.first().transform shouldBe moved
    stories.elements.last().transform shouldBe OverlayTransform()
  }

  @Test
  fun removingTheSelectedElementClearsTheSelection() {
    val stories = StoryOverlays()
    stories.addCaption()
    val id = stories.selected.shouldNotBeNull().id

    stories.remove(id)

    stories.elements shouldBe emptyList()
    stories.selectedId.shouldBeNull()
  }

  @Test
  fun bakingElementsWithNoStageRefusesAndBakingNoneAsksNothingOfIt() =
    runTest {
      val stories = StoryOverlays()
      stories.baker.bake(OUTPUT).shouldBeEmpty()
      stories.addCaption()

      assertFailsWith<IllegalStateException> { stories.baker.bake(OUTPUT) }
    }

  @Test
  fun anExportBakesEachElementOnTheFrameAndSkipsOneOffIt(): TestResult =
    runComposeUiTest {
      val state = stateOnStage()
      val stories = state.edit.stories
      stories.addSticker(StorySticker.Heart)
      stories.update(stories.selected!!.id) {
        it.copy(transform = ON_FRAME, timed = true, startSeconds = 1f, endSeconds = 2f)
      }
      stories.addSticker(StorySticker.Heart)
      stories.update(stories.selected!!.id) { it.copy(transform = OFF_FRAME) }
      waitForIdle()

      val baked = bake(stories)

      baked shouldHaveSize 1
      val overlay: ImageOverlay = baked.single()
      val bounds = ON_FRAME.boundsOn(STICKER_PX, stories.overlays.canvas, OUTPUT).shouldNotBeNull()
      overlay.position.shouldBeInstanceOf<OverlayPosition.At>().center shouldBe bounds.center
      overlay.visibleDuring shouldBe TimeRange(1.seconds, 2.seconds)
    }

  @Test
  fun theSizeSliderMovesInDoublingsAndHoldsToThePinchBounds() {
    scaleOfStep(sizeStepOf(2.5f)) shouldBe (2.5f plusOrMinus 1e-5f)
    sizeStepOf(4f) - sizeStepOf(2f) shouldBe (sizeStepOf(1f) - sizeStepOf(0.5f) plusOrMinus 1e-5f)
    scaleOfStep(SIZE_STEPS.endInclusive + 1f) shouldBe ComposedOverlayDefaults.MAX_SCALE
    scaleOfStep(SIZE_STEPS.start - 1f) shouldBe ComposedOverlayDefaults.MIN_SCALE
  }

  @Test
  fun aTurnReadsAsTheSameTurnOnTheSlider() {
    turnOf(30f) shouldBe 30f
    turnOf(200f) shouldBe -160f
    turnOf(-200f) shouldBe 160f
    turnOf(540f) shouldBe 180f
    turnOf(-180f) shouldBe -180f
    turnOf(180f) shouldBe 180f
  }

  @Test
  fun everyFaceIsOneTheDemoThemeBundles(): TestResult =
    runComposeUiTest {
      val families = mutableListOf<FontFamily?>()
      setContent { SampleTheme { StoryFont.entries.forEach { families += it.family() } } }
      waitForIdle()

      val materialDefaults =
        Typography().run { listOf(displayLarge, displaySmall, bodyMedium, labelLarge).map { it.fontFamily }.toSet() }
      families shouldHaveSize StoryFont.entries.size
      families.forEach {
        it shouldNotBe null
        materialDefaults shouldNotContain it
      }
      families.toSet() shouldHaveSize StoryFont.entries.size
    }

  @Test
  fun everyStickerBakesIntoAPictureWithSomethingDrawn(): TestResult =
    runComposeUiTest {
      val stories = stateOnStage().edit.stories

      StorySticker.entries.forEach { sticker ->
        stories.clear()
        stories.addSticker(sticker)
        waitForIdle()
        val image =
          bake(stories)
            .single()
            .image
            .shouldBeInstanceOf<ImageSource.Bytes>()
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(image.bytes))
        val drawn =
          (0 until bitmap.width).sumOf { x -> (0 until bitmap.height).count { y -> bitmap.getAlphaf(x, y) > 0.5f } }

        withClue(sticker) { drawn shouldBeGreaterThan bitmap.width * bitmap.height / 10 }
      }
    }

  // A stage the shape of the output with the stories on it, the way the demo's preview holds them.
  private fun ComposeUiTest.stateOnStage(): SampleAppState {
    val state = SampleAppState(Filmstrip { autoRegister(false) }, scope = CoroutineScope(Dispatchers.Unconfined))
    setContent {
      SampleTheme {
        val size = with(LocalDensity.current) { Modifier.size(OUTPUT.width.toDp(), OUTPUT.height.toDp()) }
        Box(size) {
          VideoStage(
            player = null,
            outputAspect = OUTPUT.width.toFloat() / OUTPUT.height,
            modifier = Modifier.fillMaxSize(),
            overlays = state.edit.stories.overlays,
          ) { StoryElements(state, interactive = false) }
        }
      }
    }
    waitForIdle()
    return state
  }

  private suspend fun ComposeUiTest.bake(stories: StoryOverlays): List<ImageOverlay> =
    coroutineScope {
      val work = async { stories.baker.bake(OUTPUT) }
      while (!work.isCompleted) {
        mainClock.advanceTimeByFrame()
        awaitIdle()
        withContext(Dispatchers.Default) { delay(1) }
      }
      work.await()
    }

  private companion object {
    val OUTPUT = Size(1280, 720)
    val ON_FRAME = OverlayTransform(Anchor(0.3f, 0.4f), scale = 1.5f, rotationDegrees = 20f)
    val OFF_FRAME = OverlayTransform(Anchor(3f, 3f))

    const val STICKER_DP = 72
    val STICKER_PX = (STICKER_DP * OVERLAY_LAYOUT_DENSITY).toInt().let { IntSize(it, it) }
  }
}
