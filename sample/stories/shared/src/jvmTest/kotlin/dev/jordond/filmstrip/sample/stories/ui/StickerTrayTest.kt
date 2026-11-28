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
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryStickerKind
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.stories.stickersMatching
import dev.jordond.filmstrip.sample.ui.SampleTheme
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StickerTrayTest {
  @Test
  fun aPickDropsTheStickerSelectedAtTheCentreAndClosesTheTray() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)

      onNodeWithContentDescription("Stickers").performClick()
      waitForIdle()
      onNodeWithTag(StoryTags.TRAY).assertExists()
      onNodeWithContentDescription(stickerLabel(StoryStickerKind.Star)).performClick()
      waitForIdle()

      val dropped = editor.story.elements.single()
      dropped.content shouldBe StoryContent.Sticker(StoryStickerKind.Star)
      dropped.transform shouldBe OverlayTransform(Anchor.Center)
      editor.story.selectedId shouldBe dropped.id
      editor.tool shouldBe StoryTool.None
      onNodeWithTag(StoryTags.TRAY).assertDoesNotExist()
    }

  @Test
  fun aTapOnAPlacedStickerStepsItsStyleAndWraps() =
    runComposeUiTest {
      val editor = StoryEditor()
      val kind = StoryStickerKind.Tag
      kind.styles shouldBeGreaterThan 2
      editor.story.add(StoryContent.Sticker(kind))
      show(editor)

      val seen =
        List(kind.styles) {
          onNodeWithTag(STAGE).performTouchInput {
            down(Offset(WIDTH / 2, HEIGHT / 2))
            up()
          }
          waitForIdle()
          (
            editor.story.elements
              .single()
              .content as StoryContent.Sticker
          ).style
        }

      seen shouldContainExactly (1 until kind.styles).toList() + 0
    }

  @Test
  fun aTapOnAStickerWithOneStyleChangesNothing() =
    runComposeUiTest {
      val editor = StoryEditor()
      editor.story.add(StoryContent.Sticker(StoryStickerKind.Heart))
      val before = editor.story.elements
      show(editor)

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(WIDTH / 2, HEIGHT / 2))
        up()
      }
      waitForIdle()

      editor.story.elements shouldBe before
      editor.story.undo()
      editor.story.elements.isEmpty() shouldBe true
    }

  @Test
  fun theFilterNarrowsTheGrid() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor)
      editor.tool = StoryTool.Stickers
      waitForIdle()
      onNodeWithContentDescription(stickerLabel(StoryStickerKind.Heart)).assertExists()

      onNodeWithTag(StoryTags.TRAY_FILTER).performTextInput("clo")
      waitForIdle()

      onNodeWithContentDescription(stickerLabel(StoryStickerKind.Clock)).assertExists()
      onNodeWithContentDescription(stickerLabel(StoryStickerKind.Heart)).assertDoesNotExist()
    }

  @Test
  fun aFilterMatchesNamesAndKeywordsWhateverTheCase() {
    stickersMatching("") shouldContainExactly StoryStickerKind.entries
    stickersMatching("  TIME ") shouldContain StoryStickerKind.Clock
    stickersMatching("zzz") shouldContainExactly emptyList()
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

  private companion object {
    const val STAGE = "stage"
    const val WIDTH = 360f
    const val HEIGHT = 640f
  }
}
