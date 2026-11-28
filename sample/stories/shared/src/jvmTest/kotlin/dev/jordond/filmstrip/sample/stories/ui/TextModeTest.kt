package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.sample.SIZE_STEPS
import dev.jordond.filmstrip.sample.scaleOfStep
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryPlate
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.ui.SampleTheme
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TextModeTest {
  @Test
  fun theTextButtonAddsACentredCaptionThroughTextMode() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      onNodeWithContentDescription("Aa").performClick()
      waitForIdle()
      editor.tool shouldBe StoryTool.Text
      onNodeWithTag(StoryTags.TEXT_FIELD).performTextInput("Hello")
      onNodeWithTag(StoryTags.TEXT_DONE).performClick()
      waitForIdle()

      val added = editor.story.elements.single()
      added.content shouldBe StoryContent.Caption("Hello")
      added.transform shouldBe OverlayTransform(Anchor.Center)
      editor.story.selectedId shouldBe added.id
      editor.tool shouldBe StoryTool.None
    }

  @Test
  fun doneWithEmptyTextAddsNothing() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      onNodeWithContentDescription("Aa").performClick()
      waitForIdle()
      onNodeWithTag(StoryTags.TEXT_FIELD).performTextInput("   ")
      onNodeWithTag(StoryTags.TEXT_DONE).performClick()
      waitForIdle()

      editor.story.elements.shouldBeEmpty()
      editor.story.canUndo shouldBe false
    }

  @Test
  fun aTapOnTheBarePictureWithNothingSelectedOpensTextMode() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      tap(Offset(WIDTH * 0.5f, HEIGHT * 0.6f))

      editor.tool shouldBe StoryTool.Text
    }

  @Test
  fun aTapOnAPlacedCaptionReopensItWithItsTextAndDoneKeepsItsPlace() =
    runComposeUiTest {
      val editor = StoryEditor()
      val placed = OverlayTransform(Anchor(0.5f, PLACED_Y), scale = 2f, rotationDegrees = 20f)
      val id = editor.story.add(StoryContent.Caption("Before"), placed)
      show(editor)

      tap(Offset(WIDTH * 0.5f, HEIGHT * PLACED_Y))

      editor.draft
        .shouldNotBeNull()
        .caption.text shouldBe "Before"
      onNodeWithTag(StoryTags.TEXT_FIELD).performTextInput("!")
      onNodeWithTag(StoryTags.TEXT_DONE).performClick()
      waitForIdle()
      val edited = editor.story.elements.single()
      edited.id shouldBe id
      (edited.content as StoryContent.Caption).text.toSet() shouldBe "Before!".toSet()
      edited.transform shouldBe placed
    }

  @Test
  fun emptyingAPlacedCaptionRemovesIt() =
    runComposeUiTest {
      val editor = StoryEditor()
      val id = editor.story.add(StoryContent.Caption("Gone"))
      show(editor)

      editor.openText(id)
      editor.editDraft { it.copy(text = "") }
      waitForIdle()
      onNodeWithTag(StoryTags.TEXT_DONE).performClick()
      waitForIdle()

      editor.story.elements.shouldBeEmpty()
    }

  @Test
  fun thePlateButtonStepsThroughNoneSolidTranslucentAndWraps() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)
      editor.openText()
      waitForIdle()

      val seen =
        List(StoryPlate.entries.size) {
          onNodeWithTag(StoryTags.TEXT_PLATE).performClick()
          waitForIdle()
          editor.draft
            .shouldNotBeNull()
            .caption.plate
        }

      seen shouldContainExactly listOf(StoryPlate.Solid, StoryPlate.Translucent, StoryPlate.None)
    }

  @Test
  fun theSizeSliderAtItsMiddleGivesTheScaleOfTheMiddleStep() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)
      editor.openText()
      editor.draft = editor.draft.shouldNotBeNull().copy(scale = 3f)
      waitForIdle()

      onNodeWithTag(StoryTags.TEXT_SIZE).performTouchInput {
        down(center)
        up()
      }
      waitForIdle()

      val middle = (SIZE_STEPS.start + SIZE_STEPS.endInclusive) / 2
      editor.draft.shouldNotBeNull().scale shouldBe (scaleOfStep(middle) plusOrMinus 1e-2f)
    }

  @Test
  fun theSizeSetInTextModeIsTheScaleTheCaptionIsPlacedAt() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)
      editor.openText()
      editor.editDraft { it.copy(text = "Big") }
      editor.draft = editor.draft.shouldNotBeNull().copy(scale = 2.5f)
      waitForIdle()

      onNodeWithTag(StoryTags.TEXT_DONE).performClick()
      waitForIdle()

      editor.story.elements
        .single()
        .transform.scale shouldBe 2.5f
      editor.draft.shouldBeNull()
      editor.story.undo()
      editor.story.elements.shouldBeEmpty()
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

  private fun ComposeUiTest.tap(at: Offset) {
    onNodeWithTag(STAGE).performTouchInput {
      down(at)
      up()
    }
    waitForIdle()
  }

  private companion object {
    const val STAGE = "stage"
    const val WIDTH = 360f
    const val HEIGHT = 640f
    const val PLACED_Y = 0.3f
  }
}
