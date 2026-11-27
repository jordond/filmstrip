package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.sample.SIZE_STEPS
import dev.jordond.filmstrip.sample.scaleOfStep
import dev.jordond.filmstrip.sample.sizeStepOf
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.stories.StoryElement
import dev.jordond.filmstrip.sample.stories.StoryFace
import dev.jordond.filmstrip.sample.stories.next
import dev.jordond.filmstrip.sample.turnOf
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlin.math.roundToInt

/**
 * The desktop's panel for the selected element: a caption's face, colour, plate, alignment and entrance, then any
 * element's size, turn and timing, and a button to delete it.
 *
 * Every control writes through the editor, so a slider here and a drag on the picture move the same element the same
 * way.
 */
@Composable
internal fun Inspector(
  editor: StoryEditor,
  preview: StoryPreview?,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .width(Tokens.sizeInspector)
        .fillMaxHeight()
        .background(Tokens.panel)
        .verticalScroll(rememberScrollState())
        .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    val element = editor.story.selected
    if (element == null) {
      Text(
        "Nothing selected. Add text or a sticker from the rail, or click an element on the picture.",
        style = MaterialTheme.typography.bodyMedium,
        color = Tokens.muted,
      )
      return@Column
    }
    (element.content as? StoryContent.Caption)?.let { CaptionControls(editor, element.id, it) }
    PlaceControls(editor, element)
    if (preview != null) TimingBar(editor, preview.renderer, preview.composition)
    Pill("Delete", Modifier.testTag(StoryTags.INSPECTOR_DELETE)) { editor.story.remove(element.id) }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionControls(
  editor: StoryEditor,
  id: Int,
  caption: StoryContent.Caption,
) {
  val change = { edit: (StoryContent.Caption) -> StoryContent.Caption ->
    editor.story.update(id) { it.copy(content = edit(caption)) }
  }
  Section("Text") {
    Pill("Edit text") { editor.openText(id) }
  }
  Section("Face") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      StoryFace.entries.forEach { face ->
        Pill(face.name, selected = face == caption.face) { change { it.copy(face = face) } }
      }
    }
  }
  Section("Colour") { ColorRow(caption.color) { color -> change { it.copy(color = color) } } }
  Section("Look") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Pill("Plate ${caption.plate.name}") { change { it.copy(plate = it.plate.next()) } }
      Pill("Align ${caption.align.name}") { change { it.copy(align = it.align.next()) } }
      Pill("Entrance ${caption.entrance.name}") { change { it.copy(entrance = it.entrance.next()) } }
    }
  }
}

@Composable
private fun PlaceControls(
  editor: StoryEditor,
  element: StoryElement,
) {
  val placed = element.transform
  val turn = turnOf(placed.rotationDegrees)
  Section("Size and turn") {
    SliderRow(
      label = "Size",
      value = sizeStepOf(placed.scale),
      valueLabel = "${(placed.scale * PERCENT).roundToInt()}%",
      range = SIZE_STEPS,
      onValueChange = { step ->
        editor.moved(element.id, OverlayTransform(placed.center, scaleOfStep(step), placed.rotationDegrees))
      },
      onReset = { editor.moved(element.id, OverlayTransform(placed.center, 1f, placed.rotationDegrees)) },
    )
    SliderRow(
      label = "Turn",
      value = turn,
      valueLabel = "${turn.roundToInt()}°",
      range = -HALF_TURN..HALF_TURN,
      onValueChange = { degrees -> editor.moved(element.id, OverlayTransform(placed.center, placed.scale, degrees)) },
      onReset = { editor.moved(element.id, OverlayTransform(placed.center, placed.scale, 0f)) },
    )
  }
}

@Composable
private fun Section(
  title: String,
  content: @Composable () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Tokens.muted)
    content()
  }
}

private const val PERCENT = 100
private const val HALF_TURN = 180f
