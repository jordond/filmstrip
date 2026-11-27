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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.sample.stories.STORY_ASPECT
import dev.jordond.filmstrip.sample.stories.StoryBrush
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryStroke
import dev.jordond.filmstrip.sample.stories.StoryTool
import dev.jordond.filmstrip.sample.ui.SampleTheme
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DrawModeTest {
  @Test
  fun aStrokeAcrossAnUprightAndASidewaysStageStoresTheSameFractions() {
    val upright = strokeAcross(width = 360f, height = 640f)
    val sideways = strokeAcross(width = 640f, height = 360f)

    listOf(upright, sideways).forEach { stroke ->
      stroke.points.first().x shouldBe (FROM.x plusOrMinus TOLERANCE)
      stroke.points.first().y shouldBe (FROM.y plusOrMinus TOLERANCE)
      stroke.points.last().x shouldBe (TO.x plusOrMinus TOLERANCE)
      stroke.points.last().y shouldBe (TO.y plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun theDrawButtonOpensDrawModeAndDoneLeavesIt() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor, 360f, 640f)

      onNodeWithContentDescription("Draw").performClick()
      waitForIdle()
      editor.tool shouldBe StoryTool.Draw
      onNodeWithTag(StoryTags.DRAW_DONE).performClick()
      waitForIdle()

      editor.tool shouldBe StoryTool.None
    }

  @Test
  fun aStrokeTakesTheBrushPickedAndUndoDropsIt() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor, 360f, 640f)
      editor.openDraw()
      waitForIdle()

      onNodeWithContentDescription("Brush ${StoryBrush.Marker.name}").performClick()
      waitForIdle()
      drag(Offset(100f, 300f), Offset(260f, 340f))
      editor.story.strokes
        .shouldHaveSize(1)
        .single()
        .brush shouldBe StoryBrush.Marker
      onNodeWithTag(StoryTags.DRAW_UNDO).performClick()
      waitForIdle()

      editor.story.strokes.shouldBeEmpty()
    }

  @Test
  fun aPressWithNoMoveLeavesADot() =
    runComposeUiTest {
      val editor = StoryEditor()
      show(editor, 360f, 640f)
      editor.openDraw()
      waitForIdle()

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(180f, 320f))
        up()
      }
      waitForIdle()

      editor.story.strokes
        .single()
        .points shouldHaveSize 1
    }

  private fun strokeAcross(
    width: Float,
    height: Float,
  ): StoryStroke {
    val editor = StoryEditor()
    runComposeUiTest {
      show(editor, width, height)
      editor.openDraw()
      waitForIdle()
      val pictureWidth = minOf(width, height * STORY_ASPECT.value)
      val pictureHeight = pictureWidth / STORY_ASPECT.value
      val left = (width - pictureWidth) / 2
      val top = (height - pictureHeight) / 2

      fun at(point: Anchor) = Offset(left + point.x * pictureWidth, top + point.y * pictureHeight)
      drag(at(FROM), at(TO))
    }
    return editor.story.strokes.single()
  }

  private fun ComposeUiTest.drag(
    from: Offset,
    to: Offset,
  ) {
    onNodeWithTag(STAGE).performTouchInput {
      down(from)
      moveTo((from + to) / 2f)
      moveTo(to)
      up()
    }
    waitForIdle()
  }

  private fun ComposeUiTest.show(
    editor: StoryEditor,
    width: Float,
    height: Float,
  ) {
    setContent {
      SampleTheme {
        val size = with(LocalDensity.current) { Modifier.size(width.toDp(), height.toDp()) }
        Box(size.testTag(STAGE)) { TouchEditor(editor, preview = null, output = OUTPUT) }
      }
    }
    waitForIdle()
  }

  private companion object {
    const val STAGE = "stage"
    const val TOLERANCE = 0.01f
    val FROM = Anchor(0.3f, 0.45f)
    val TO = Anchor(0.7f, 0.55f)
    val OUTPUT = Size(1080, 1920)
  }
}
