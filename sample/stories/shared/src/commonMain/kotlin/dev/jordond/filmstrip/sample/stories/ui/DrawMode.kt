package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.sample.stories.DRAWING_TRANSFORM
import dev.jordond.filmstrip.sample.stories.StoryBrush
import dev.jordond.filmstrip.sample.stories.StoryDrawingContent
import dev.jordond.filmstrip.sample.stories.StoryEditor

/**
 * The story's drawing over the picture, with the stroke under the finger on top of it.
 *
 * It is one element the size of the frame that takes no touch, drawn the way the export bakes it.
 */
@Composable
internal fun VideoStageScope.StoryDrawing(
  editor: StoryEditor,
  output: Size,
) {
  val strokes = editor.story.strokes + listOfNotNull(editor.pendingStroke)
  if (strokes.isEmpty()) return
  ComposedOverlay(
    transform = DRAWING_TRANSFORM,
    canvas = editor.canvas,
    output = output,
    onTransformChange = { },
    enabled = false,
  ) { StoryDrawingContent(strokes) }
}

/**
 * Takes every touch on the stage while draw mode is open and turns it into a stroke, its points fractions of the
 * picture.
 */
@Composable
internal fun VideoStageScope.DrawInput(editor: StoryEditor) {
  val picture by rememberUpdatedState(frame.contentRect)
  Box(
    Modifier.matchParentSize().pointerInput(editor) {
      awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        editor.strokeStarted(down.position.within(picture))
        drag(down.id) { change ->
          change.consume()
          editor.strokeMoved(change.position.within(picture))
        }
        editor.strokeEnded()
      }
    },
  )
}

/**
 * Draw mode's controls: the brushes with Undo and Done along the top, the width slider on the left edge and the
 * colours along the bottom.
 */
@Composable
internal fun DrawModeControls(
  editor: StoryEditor,
  modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxSize().padding(EDGE)) {
    Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(GAP)) {
      StoryBrush.entries.forEach { brush ->
        Pill(
          label = brush.name,
          modifier = Modifier.semantics { contentDescription = "Brush ${brush.name}" },
          selected = editor.brush == brush,
        ) { editor.brush = brush }
      }
    }
    Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(GAP)) {
      RoundButton(
        label = "Undo stroke",
        onClick = editor::undoStroke,
        modifier = Modifier.testTag(StoryTags.DRAW_UNDO),
        icon = StoryIcon.Undo,
        enabled = editor.drawnStrokes > 0,
      )
      Pill("Done", Modifier.testTag(StoryTags.DRAW_DONE), selected = true, onClick = editor::closeDraw)
    }
    StepSlider(
      step = editor.strokeWidthDp,
      steps = WIDTHS,
      label = "Width",
      modifier = Modifier.align(Alignment.CenterStart).testTag(StoryTags.DRAW_WIDTH),
    ) { editor.strokeWidthDp = it }
    ColorRow(editor.strokeColor, Modifier.align(Alignment.BottomCenter).padding(bottom = COLORS_LIFT)) {
      editor.strokeColor = it
    }
  }
}

private fun Offset.within(picture: Rect): Anchor =
  Anchor(
    ((x - picture.left) / picture.width).coerceIn(0f, 1f),
    ((y - picture.top) / picture.height).coerceIn(0f, 1f),
  )

private val WIDTHS = 2f..24f
private val EDGE = 12.dp
private val GAP = 8.dp
private val COLORS_LIFT = 56.dp
