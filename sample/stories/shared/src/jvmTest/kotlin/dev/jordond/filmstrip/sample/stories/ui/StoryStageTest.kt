package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.sample.stories.BIN_CENTER
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.testing.FakeVideoPlayer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class StoryStageTest {
  @Test
  fun aDragMovesTheElementAndDrawsAGuideWhileItSitsOnTheCentreLine() =
    runComposeUiTest {
      val editor = editorWith("Dragged")
      show(editor)

      onNodeWithTag(STAGE).performTouchInput { down(START) }
      repeat(STEPS) {
        onNodeWithTag(STAGE).performTouchInput { moveBy(Offset(0f, STEP)) }
        waitForIdle()
      }

      onNodeWithTag(StoryTags.GUIDE_VERTICAL).assertExists()
      onNodeWithTag(StoryTags.GUIDE_HORIZONTAL).assertDoesNotExist()

      onNodeWithTag(STAGE).performTouchInput { up() }
      waitForIdle()

      onNodeWithTag(StoryTags.GUIDE_VERTICAL).assertDoesNotExist()
      editor.story.elements
        .single()
        .transform.center.y shouldBe (START_Y + STEPS * STEP / HEIGHT plusOrMinus 1e-3f)
    }

  @Test
  fun aDragReleasedOverTheBinRemovesTheElementAsOneUndoStep() =
    runComposeUiTest {
      val editor = editorWith("Binned")
      show(editor)

      dragDownTo(BIN_CENTER.y)

      editor.story.elements.shouldBeEmpty()
      editor.story.undo()
      editor.story.elements
        .single()
        .transform.center.y shouldBe START_Y
    }

  @Test
  fun aDragReleasedShortOfTheBinKeepsTheElement() =
    runComposeUiTest {
      val editor = editorWith("Kept")
      show(editor)

      dragDownTo(SHORT_OF_BIN)

      editor.story.elements
        .single()
        .transform.center.y shouldBe (SHORT_OF_BIN plusOrMinus 1e-3f)
    }

  @Test
  fun theBinShowsOnlyDuringAGesture() =
    runComposeUiTest {
      val editor = editorWith("Held")
      show(editor)
      onNodeWithTag(StoryTags.BIN).assertDoesNotExist()

      onNodeWithTag(STAGE).performTouchInput {
        down(START)
        moveBy(Offset(0f, STEP))
      }
      waitForIdle()
      onNodeWithTag(StoryTags.BIN).assertExists()

      onNodeWithTag(STAGE).performTouchInput { up() }
      waitForIdle()
      onNodeWithTag(StoryTags.BIN).assertDoesNotExist()
    }

  @Test
  fun aTapOnAnUnselectedElementSelectsItWithoutMovingIt() =
    runComposeUiTest {
      val editor = editorWith("Under")
      val under = editor.story.elements.single()
      val over = editor.story.add(StoryContent.Caption("Over"))
      editor.story.transform(over, OverlayTransform(Anchor(0.5f, 0.7f)))
      show(editor)

      onNodeWithTag(STAGE).performTouchInput {
        down(START)
        up()
      }
      waitForIdle()

      editor.story.selectedId shouldBe under.id
      editor.story.elements
        .first { it.id == under.id }
        .transform shouldBe under.transform
    }

  @Test
  fun aTapOnTheBarePictureClearsTheSelection() =
    runComposeUiTest {
      val editor = editorWith("Selected")
      show(editor)

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(WIDTH * 0.5f, HEIGHT * 0.6f))
        up()
      }
      waitForIdle()

      editor.story.selectedId shouldBe null
    }

  @Test
  fun theChromeIsAbsentDuringAGestureAndBackAfter() =
    runComposeUiTest {
      val editor = editorWith("Moved")
      show(editor)
      onNodeWithTag(StoryTags.CHROME).assertExists()

      onNodeWithTag(STAGE).performTouchInput {
        down(START)
        moveBy(Offset(0f, STEP))
      }
      waitForIdle()
      onNodeWithTag(StoryTags.CHROME).assertDoesNotExist()

      onNodeWithTag(STAGE).performTouchInput { up() }
      waitForIdle()
      onNodeWithTag(StoryTags.CHROME).assertExists()
    }

  @Test
  fun thePlayheadHidesAnElementOutsideItsWindowUnlessItIsSelected() =
    runComposeUiTest {
      val clock = TestScope()
      val player = FakeVideoPlayer(clock)
      val editor = editorWith("Later")
      val id =
        editor.story.elements
          .single()
          .id
      editor.story.update(id) { it.copy(timed = true, startSeconds = WINDOW_START, endSeconds = WINDOW_END) }
      editor.story.select(null)
      setContent {
        SampleTheme {
          val size = with(LocalDensity.current) { Modifier.size(WIDTH.toDp(), HEIGHT.toDp()) }
          Box(size.testTag(STAGE)) { StoryStage(editor, player) }
        }
      }

      seek(player, clock, INSIDE_WINDOW)
      dragDownFrom(START)
      editor.story.selectedId shouldBe id
      val moved =
        editor.story.elements
          .single()
          .transform.center.y
      moved shouldBe (START_Y + STEPS * STEP / HEIGHT plusOrMinus 1e-3f)

      seek(player, clock, OUTSIDE_WINDOW)
      dragDownFrom(Offset(START.x, moved * HEIGHT))
      val held =
        editor.story.elements
          .single()
          .transform.center.y
      held shouldBe (moved + STEPS * STEP / HEIGHT plusOrMinus 1e-3f)

      editor.story.select(null)
      waitForIdle()
      dragDownFrom(Offset(START.x, held * HEIGHT))
      editor.story.selectedId.shouldBeNull()
      editor.story.elements
        .single()
        .transform.center.y shouldBe held
    }

  private fun ComposeUiTest.seek(
    player: FakeVideoPlayer,
    clock: TestScope,
    position: Duration,
  ) {
    player.seekTo(position)
    clock.testScheduler.runCurrent()
    waitForIdle()
    mainClock.advanceTimeByFrame()
    waitForIdle()
  }

  private fun ComposeUiTest.dragDownFrom(start: Offset) {
    onNodeWithTag(STAGE).performTouchInput { down(start) }
    repeat(STEPS) {
      onNodeWithTag(STAGE).performTouchInput { moveBy(Offset(0f, STEP)) }
      waitForIdle()
    }
    onNodeWithTag(STAGE).performTouchInput { up() }
    waitForIdle()
  }

  private fun editorWith(text: String): StoryEditor =
    StoryEditor().also { editor ->
      val id = editor.story.add(StoryContent.Caption(text))
      editor.story.transform(id, OverlayTransform(Anchor(0.5f, START_Y)))
    }

  private fun ComposeUiTest.show(editor: StoryEditor) {
    setContent {
      SampleTheme {
        val size = with(LocalDensity.current) { Modifier.size(WIDTH.toDp(), HEIGHT.toDp()) }
        Box(size.testTag(STAGE)) { TouchEditor(editor, preview = null) }
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.dragDownTo(y: Float) {
    val step = (y - START_Y) * HEIGHT / STEPS
    onNodeWithTag(STAGE).performTouchInput {
      down(START)
      repeat(STEPS) { moveBy(Offset(0f, step)) }
      up()
    }
    waitForIdle()
  }

  private companion object {
    const val STAGE = "stage"
    const val WIDTH = 360f
    const val HEIGHT = 640f
    const val START_Y = 0.2f
    const val SHORT_OF_BIN = 0.6f
    const val STEPS = 8
    const val STEP = 10f
    const val WINDOW_START = 2f
    const val WINDOW_END = 4f
    val INSIDE_WINDOW = 3.seconds
    val OUTSIDE_WINDOW = 1.seconds
    val START = Offset(WIDTH * 0.5f, HEIGHT * START_Y)
  }
}
