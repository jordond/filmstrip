package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.sample.stories.ELEMENT_BINDINGS
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryLook
import dev.jordond.filmstrip.sample.stories.StoryShortcut
import dev.jordond.filmstrip.sample.stories.StoryStickerKind
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.StoryToolButton
import dev.jordond.filmstrip.sample.stories.next
import dev.jordond.filmstrip.sample.stories.perform
import dev.jordond.filmstrip.sample.ui.SampleTheme
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PointerEditorTest {
  @Test
  fun everyToolButtonIsInBothShells() {
    runComposeUiTest {
      show(StoryEditor())
      StoryToolButton.entries.forEach { onNodeWithContentDescription(it.label).assertExists() }
    }
    runComposeUiTest {
      setContent { SampleTheme { TouchEditor(StoryEditor(), preview = null) } }
      waitForIdle()
      StoryToolButton.entries.forEach { onNodeWithContentDescription(it.label).assertExists() }
    }
  }

  @Test
  fun aRailButtonOpensItsTool() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      onNodeWithContentDescription(StoryToolButton.Stickers.label).performClick()
      waitForIdle()

      editor.tool shouldBe StoryTool.Stickers
    }

  @Test
  fun deleteRemovesTheSelection() =
    runComposeUiTest {
      val editor = editorWithSticker()
      show(editor)

      keys { pressKey(Key.Delete) }

      editor.story.elements.shouldBeEmpty()
    }

  @Test
  fun escapeClearsTheSelection() =
    runComposeUiTest {
      val editor = editorWithSticker()
      show(editor)

      keys { pressKey(Key.Escape) }

      editor.story.selectedId.shouldBeNull()
      editor.story.elements.size shouldBe 1
    }

  @Test
  fun controlZUndoesAndWithShiftRedoes() =
    runComposeUiTest {
      val editor = editorWithSticker()
      show(editor)

      keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
      editor.story.elements.shouldBeEmpty()

      keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
      editor.story.elements.size shouldBe 1
    }

  @Test
  fun anArrowKeyNudgesTheSelectionByTheLibrarysStep() =
    runComposeUiTest {
      val editor = editorWithSticker()
      editor.story.transform(editor.story.selectedId!!, OverlayTransform(OFF_CENTRE))
      show(editor)

      keys { repeat(PRESSES) { pressKey(Key.DirectionRight) } }

      val moved =
        editor.story.elements
          .single()
          .transform.center
      moved.x shouldBe (OFF_CENTRE.x + PRESSES * ComposedOverlayDefaults.NUDGE plusOrMinus 1e-4f)
      moved.y shouldBe OFF_CENTRE.y
    }

  @Test
  fun theLookKeysStepTheLookBothWays() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      keys { pressKey(Key.Period) }
      editor.story.look shouldBe StoryLook.Original.next()
      keys { repeat(2) { pressKey(Key.Comma) } }
      editor.story.look shouldBe StoryLook.Original.previous()
    }

  @Test
  fun theToolKeysOpenTheirToolsAndEscapeClosesThem() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      keys { pressKey(Key.S) }
      editor.tool shouldBe StoryTool.Stickers
      editor.perform(StoryShortcut.Escape) { }
      waitForIdle()
      keys { pressKey(Key.D) }
      editor.tool shouldBe StoryTool.Draw
    }

  @Test
  fun aTouchOnTheCornerHandleSizesTheSelectionSoTheHandlesAreAlwaysDrawn() =
    runComposeUiTest {
      val editor = editorWithSticker()
      show(editor)
      val stage = onNodeWithTag(StoryTags.STAGE).fetchSemanticsNode().boundsInRoot
      val half = stage.width * STICKER_SHARE / 2
      val host = onNodeWithTag(StoryTags.POINTER_STAGE).fetchSemanticsNode().boundsInRoot
      val corner = stage.center + Offset(half, half) - host.topLeft

      onNodeWithTag(StoryTags.POINTER_STAGE).performTouchInput {
        down(corner)
        repeat(PRESSES) { moveBy(Offset(half / PRESSES, half / PRESSES)) }
        up()
      }
      waitForIdle()

      editor.story.elements
        .single()
        .transform.scale shouldBeGreaterThan 1.2f
    }

  @Test
  fun theLegendListsEveryBinding() =
    runComposeUiTest {
      show(StoryEditor())

      StoryShortcut.entries.forEach { onNodeWithTag(StoryTags.legend(it.name)).assertExists() }
      ELEMENT_BINDINGS.forEach { onNodeWithTag(StoryTags.legend(it.keys)).assertExists() }
    }

  private fun editorWithSticker(): StoryEditor =
    StoryEditor().also { it.story.add(StoryContent.Sticker(StoryStickerKind.Heart)) }

  private fun ComposeUiTest.keys(block: KeyInjectionScope.() -> Unit) {
    onNodeWithTag(StoryTags.POINTER_STAGE).performKeyInput(block)
    waitForIdle()
  }

  private fun ComposeUiTest.show(editor: StoryEditor) {
    setContent {
      SampleTheme {
        val size = with(LocalDensity.current) { Modifier.size(WIDTH.toDp(), HEIGHT.toDp()) }
        Box(size) { PointerEditor(editor, preview = null) }
      }
    }
    waitForIdle()
  }

  private companion object {
    const val WIDTH = 1200f
    const val HEIGHT = 800f
    const val PRESSES = 4
    const val STICKER_SHARE = 72f / 360f
    val OFF_CENTRE = Anchor(0.3f, 0.3f)
  }
}
