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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.sample.stories.StoryClock
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryLook
import dev.jordond.filmstrip.sample.stories.next
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.still.FrameRenderer
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class TimingAndLooksTest {
  @Test
  fun theBarsHandlesWriteTheElementsWindowAsOneUndoStep() =
    runComposeUiTest {
      val editor = StoryEditor()
      val id = editor.story.add(StoryContent.Caption("Timed"))
      setContent {
        SampleTheme {
          val size = with(LocalDensity.current) { Modifier.size(WIDTH.toDp(), BAR_HEIGHT.toDp()) }
          Box(size) { TimingBar(editor, StoryClock.still(DURATION), NoFrames, Composition(tracks = emptyList())) }
        }
      }
      waitForIdle()

      onNodeWithTag(StoryTags.TIMING_STRIP).performTouchInput {
        down(Offset(HANDLE_INSET, height / 2f))
        repeat(DRAG_STEPS) { moveBy(Offset(width * QUARTER / DRAG_STEPS, 0f)) }
        up()
      }
      waitForIdle()

      val timed = editor.story.elements.single { it.id == id }
      timed.timed shouldBe true
      timed.startSeconds shouldBe (DURATION.inWholeSeconds * QUARTER plusOrMinus HANDLE_TOLERANCE)
      timed.endSeconds shouldBe (DURATION.inWholeSeconds.toFloat() plusOrMinus HANDLE_TOLERANCE)
      editor.story.undo()
      editor.story.elements
        .single { it.id == id }
        .timed shouldBe false
    }

  @Test
  fun theWholeStoryButtonClearsTheWindow() =
    runComposeUiTest {
      val editor = StoryEditor()
      val id = editor.story.add(StoryContent.Caption("Timed"))
      editor.story.window(id, timed = true, startSeconds = 2f, endSeconds = 4f)
      setContent {
        SampleTheme { TimingBar(editor, StoryClock.still(DURATION), NoFrames, Composition(tracks = emptyList())) }
      }
      waitForIdle()

      onNodeWithTag(StoryTags.TIMING_WHOLE).performClick()
      waitForIdle()

      editor.story.elements
        .single()
        .window
        .shouldBeNull()
    }

  @Test
  fun aSwipeAcrossTheBarePictureStepsTheLookAndWraps() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      swipe(fromX = WIDTH * 0.8f, toX = WIDTH * 0.2f)
      editor.story.look shouldBe StoryLook.Original.next()
      onNodeWithTag(StoryTags.LOOK_NAME).assertExists()

      swipe(fromX = WIDTH * 0.2f, toX = WIDTH * 0.8f)
      swipe(fromX = WIDTH * 0.2f, toX = WIDTH * 0.8f)
      editor.story.look shouldBe StoryLook.Original.previous()
    }

  @Test
  fun aSwipeThatStartsOnAnElementMovesItAndKeepsTheLook() =
    runComposeUiTest {
      val editor = StoryEditor()
      editor.story.add(StoryContent.Caption("In the way"))
      show(editor)

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(WIDTH / 2, HEIGHT / 2))
        repeat(DRAG_STEPS) { moveBy(Offset(-WIDTH * QUARTER / DRAG_STEPS, 0f)) }
        up()
      }
      waitForIdle()

      editor.story.look shouldBe StoryLook.Original
      editor.story.elements
        .single()
        .transform.center.x shouldBe (0.5f - QUARTER plusOrMinus 1e-2f)
    }

  private fun ComposeUiTest.swipe(
    fromX: Float,
    toX: Float,
  ) {
    onNodeWithTag(STAGE).performTouchInput {
      down(Offset(fromX, HEIGHT * SWIPE_Y))
      repeat(DRAG_STEPS) { moveBy(Offset((toX - fromX) / DRAG_STEPS, 0f)) }
      up()
    }
    waitForIdle()
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

  private object NoFrames : FrameRenderer {
    override suspend fun frame(
      composition: Composition,
      at: Duration,
      heightPx: Int?,
      exportSpec: ExportSpec,
    ): FrameResult = awaitCancellation()

    override fun frames(
      composition: Composition,
      at: List<Duration>,
      heightPx: Int?,
      exportSpec: ExportSpec,
    ): Flow<FrameResult> = emptyFlow()
  }

  private companion object {
    const val STAGE = "stage"
    const val WIDTH = 360f
    const val HEIGHT = 640f
    const val BAR_HEIGHT = 160f
    const val SWIPE_Y = 0.8f
    const val QUARTER = 0.25f
    const val DRAG_STEPS = 6
    const val HANDLE_INSET = 6f
    const val HANDLE_TOLERANCE = 0.6f
    val DURATION = 10.seconds
  }
}
