package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.compose.ui.interaction.composedOverlayKeys
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.SIZE_STEPS
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.StoryContent
import dev.jordond.filmstrip.sample.StoryElement
import dev.jordond.filmstrip.sample.StoryFont
import dev.jordond.filmstrip.sample.StorySticker
import dev.jordond.filmstrip.sample.scaleOfStep
import dev.jordond.filmstrip.sample.sizeStepOf
import dev.jordond.filmstrip.sample.turnOf
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwatchRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import kotlin.math.roundToInt

/**
 * How a story element is drawn, both live on the stage and when it is baked.
 */
@Composable
internal fun StoryElementContent(element: StoryElement) {
  when (val content = element.content) {
    is StoryContent.Caption -> {
      val plate = content.plate
      Text(
        text = content.text,
        color = Color(content.color),
        fontSize = CAPTION_SIZE.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = content.font.family(),
        textAlign = TextAlign.Center,
        modifier =
          if (plate == null) {
            Modifier.padding(horizontal = 8.dp)
          } else {
            Modifier.background(Color(plate), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
          },
      )
    }
    is StoryContent.Sticker -> {
      StoryStickerArt(content.sticker)
    }
  }
}

/**
 * Every story element over the stage, taking gestures only while the stories tool is open.
 */
@Composable
internal fun VideoStageScope.StoryElements(
  state: SampleAppState,
  interactive: Boolean,
) {
  val stories = state.edit.stories
  val picture = frame.contentRect
  val output =
    state.preview.previewOutput
      ?: Size(picture.width.roundToInt(), picture.height.roundToInt()).takeIf { it.width > 0 && it.height > 0 }
      ?: return
  val colors = sampleHandleColors()
  val focus = remember { FocusRequester() }
  val selected = stories.selected
  // An empty layer under the elements holds the keyboard while the tool is open, so the keys move the selected one.
  Box(
    Modifier
      .matchParentSize()
      .focusRequester(focus)
      .composedOverlayKeys(
        transform = selected?.transform ?: OverlayTransform(),
        onTransformChange = { next -> selected?.let { held -> stories.update(held.id) { it.copy(transform = next) } } },
        enabled = interactive && selected != null,
      ),
  )
  stories.elements.forEach { element ->
    key(element.id) {
      ComposedOverlay(
        transform = element.transform,
        canvas = stories.canvas,
        output = output,
        onTransformChange = { next -> stories.update(element.id) { it.copy(transform = next) } },
        selected = interactive && stories.selectedId == element.id,
        onSelect = {
          stories.selectedId = element.id
          focus.requestFocus()
        },
        colors = colors,
        enabled = interactive,
      ) { StoryElementContent(element) }
    }
  }
}

/**
 * The stories tool, adding text and stickers and setting the selected one's look and when it shows.
 */
@Composable
internal fun StoriesPanel(state: SampleAppState) {
  val stories = state.edit.stories
  val duration = state.editedDurationSeconds

  ToolSection("Add") {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { stories.addCaption() }) { Text("Text") }
      StorySticker.entries.forEach { sticker ->
        OutlinedButton(onClick = { stories.addSticker(sticker) }) { Text(sticker.name) }
      }
    }
  }

  val selected = stories.selected ?: return
  val id = selected.id
  val update = { change: (StoryElement) -> StoryElement -> stories.update(id, change) }

  val caption = selected.content as? StoryContent.Caption
  if (caption != null) {
    ToolSection("Text") {
      OutlinedTextField(
        value = caption.text,
        onValueChange = { text -> update { it.copy(content = caption.copy(text = text)) } },
        label = { Text("Says") },
        modifier = Modifier.fillMaxWidth(),
      )
      ChipGroup(
        options = StoryFont.entries.map { it.name to it },
        selected = caption.font,
        onSelect = { font -> update { it.copy(content = caption.copy(font = font)) } },
      )
    }
    ToolSection("Color") {
      SwatchRow(
        colors = Palette,
        selected = caption.color,
        onSelect = { color -> update { it.copy(content = caption.copy(color = color ?: EditState.WHITE)) } },
      )
    }
    ToolSection("Plate") {
      SwatchRow(
        colors = Plates,
        selected = caption.plate,
        onSelect = { plate -> update { it.copy(content = caption.copy(plate = plate)) } },
        includeNone = true,
      )
    }
  }

  ToolSection("Size and turn") {
    SliderRow(
      label = "Size",
      value = sizeStepOf(selected.transform.scale),
      valueLabel = "${(selected.transform.scale * PERCENT).roundToInt()}%",
      range = SIZE_STEPS,
      onValueChange = { step -> update { it.scaled(scaleOfStep(step)) } },
      onReset = { update { it.scaled(1f) } },
    )
    SliderRow(
      label = "Turn",
      value = turnOf(selected.transform.rotationDegrees),
      valueLabel = "${turnOf(selected.transform.rotationDegrees).roundToInt()}°",
      range = -HALF_TURN..HALF_TURN,
      onValueChange = { degrees -> update { it.turned(degrees) } },
      onReset = { update { it.turned(0f) } },
    )
  }

  ToolSection("Visible during") {
    SwitchRow(
      label = "Only part of the edit",
      checked = selected.timed,
      onCheckedChange = { timed ->
        update { it.copy(timed = timed, startSeconds = 0f, endSeconds = duration) }
      },
    )
    if (selected.timed && duration > 0f) {
      WindowSlider(
        start = selected.startSeconds,
        end = selected.endSeconds,
        duration = duration,
        onChange = { start, end -> update { it.copy(startSeconds = start, endSeconds = end) } },
      )
    }
  }

  ToolSection("Element") {
    OutlinedButton(onClick = { stories.remove(id) }) {
      Text("Delete", color = MaterialTheme.colorScheme.error)
    }
  }
}

private fun StoryElement.scaled(scale: Float): StoryElement =
  copy(transform = OverlayTransform(transform.center, scale, transform.rotationDegrees))

private fun StoryElement.turned(degrees: Float): StoryElement =
  copy(transform = OverlayTransform(transform.center, transform.scale, degrees))

/**
 * The theme's family for this face, so it resolves the same way on the stage and inside the capture host.
 */
@Composable
internal fun StoryFont.family(): FontFamily? =
  when (this) {
    StoryFont.Display -> MaterialTheme.typography.displaySmall.fontFamily
    StoryFont.Type -> MaterialTheme.typography.bodyMedium.fontFamily
    StoryFont.Pixel -> MaterialTheme.typography.labelLarge.fontFamily
  }

private const val CAPTION_SIZE = 28
private const val PERCENT = 100
private const val HALF_TURN = 180f
