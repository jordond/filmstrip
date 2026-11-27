package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.sample.CropMode
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.EditorTool
import dev.jordond.filmstrip.sample.FillMode
import dev.jordond.filmstrip.sample.FrameMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.Sequence
import dev.jordond.filmstrip.sample.ui.ControlGroup
import dev.jordond.filmstrip.sample.ui.Led
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.bottomLine
import dev.jordond.filmstrip.sample.ui.focusRing
import dev.jordond.filmstrip.sample.ui.scanlinesFaint
import io.github.vinceglb.filekit.name

private class ToolSpec(
  val tool: EditorTool,
  val label: String,
  val icon: ImageVector,
)

private val Tools =
  listOf(
    ToolSpec(EditorTool.Clip, "Clip", SampleIcons.Trim),
    ToolSpec(EditorTool.Crop, "Crop", SampleIcons.Crop),
    ToolSpec(EditorTool.Transform, "Transform", SampleIcons.Rotate),
    ToolSpec(EditorTool.Scale, "Scale", SampleIcons.Scale),
    ToolSpec(EditorTool.Adjust, "Adjust", SampleIcons.Brightness),
    ToolSpec(EditorTool.Text, "Text", SampleIcons.TextGlyph),
    ToolSpec(EditorTool.Watermark, "Overlay", SampleIcons.Watermark),
    ToolSpec(EditorTool.Stories, "Stories", SampleIcons.TextGlyph),
    ToolSpec(EditorTool.Audio, "Audio", SampleIcons.Volume),
    ToolSpec(EditorTool.Tracks, "Tracks", SampleIcons.Tracks),
    ToolSpec(EditorTool.Background, "Frame", SampleIcons.Layers),
  )

/**
 * The tool switcher. An LED marks a tool that is contributing something to the composition right now.
 *
 * @param vertical Lay the tools out in a rail down the side of the window, rather than a scrolling row under the
 *   timeline.
 */
@Composable
public fun ToolRail(
  state: SampleAppState,
  modifier: Modifier = Modifier,
  vertical: Boolean = false,
) {
  val background = modifier.background(MaterialTheme.colorScheme.surface)
  if (vertical) {
    Column(
      background
        .widthIn(min = Tokens.sizeRail)
        .width(IntrinsicSize.Max)
        .fillMaxHeight()
        .verticalScroll(rememberScrollState()),
    ) {
      Tools.forEach { spec ->
        ToolButton(
          spec = spec,
          selected = state.activeTool == spec.tool,
          marked = state.edit.contributes(spec.tool, state.sequence),
          modifier = Modifier.fillMaxWidth().bottomLine(MaterialTheme.colorScheme.outlineVariant),
          onClick = { state.activeTool = spec.tool },
        )
      }
    }
  } else {
    Row(background.fillMaxWidth().height(IntrinsicSize.Min).horizontalScroll(rememberScrollState())) {
      Tools.forEach { spec ->
        ToolButton(
          spec = spec,
          selected = state.activeTool == spec.tool,
          marked = state.edit.contributes(spec.tool, state.sequence),
          modifier = Modifier.widthIn(min = ROW_TOOL_WIDTH.dp),
          onClick = { state.activeTool = spec.tool },
        )
        VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
  }
}

@Composable
private fun ToolButton(
  spec: ToolSpec,
  selected: Boolean,
  marked: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interaction = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  val tint = if (selected) colors.onPrimary else Tokens.mutedStrong

  Box(
    modifier =
      modifier
        .focusRing(interaction, inset = 6.dp)
        .background(if (selected) colors.primary else Color.Transparent)
        .selectable(
          selected = selected,
          role = Role.Tab,
          interactionSource = interaction,
          indication = null,
          onClick = onClick,
        ).padding(top = 10.dp, bottom = 8.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Icon(spec.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
      Text(
        text = spec.label.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 18.sp),
        color = tint,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
    if (marked) {
      Led(LedState.Ready, Modifier.align(Alignment.TopEnd).padding(end = 12.dp))
    }
  }
}

/**
 * The controls for whichever tool is selected, one bordered section to a group.
 *
 * @param titled Show the tool's name on a scanline strip above the controls, for a column that has the height.
 */
@Composable
public fun ToolPanel(
  state: SampleAppState,
  modifier: Modifier = Modifier,
  titled: Boolean = false,
) {
  Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    if (titled) {
      Text(
        text = Tools.first { it.tool == state.activeTool }.label.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
          Modifier
            .fillMaxWidth()
            .scanlinesFaint()
            .bottomLine(MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
    when (state.activeTool) {
      EditorTool.Clip -> ClipPanel(state)
      EditorTool.Crop -> CropPanel(state)
      EditorTool.Transform -> TransformPanel(state)
      EditorTool.Scale -> ScalePanel(state)
      EditorTool.Adjust -> AdjustPanel(state)
      EditorTool.Text -> TextPanel(state)
      EditorTool.Watermark -> WatermarkPanel(state)
      EditorTool.Stories -> StoriesPanel(state)
      EditorTool.Audio -> AudioPanel(state)
      EditorTool.Tracks -> TracksPanel(state)
      EditorTool.Background -> BackgroundPanel(state)
    }
  }
}

/**
 * One group of a tool's controls, closed off from the next by a line.
 */
@Composable
internal fun ToolSection(
  label: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  ControlGroup(
    label = label,
    modifier =
      Modifier
        .bottomLine(MaterialTheme.colorScheme.outlineVariant)
        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    content = content,
  )
}

/**
 * Whether a tool is putting anything into the composition, which is what marks it on the rail.
 */
private fun EditState.contributes(
  tool: EditorTool,
  sequence: Sequence,
): Boolean =
  when (tool) {
    EditorTool.Clip -> {
      sequence.clips.any { it.touched }
    }
    EditorTool.Crop -> {
      cropMode != CropMode.Off
    }
    EditorTool.Transform -> {
      transform.touched || sequence.clips.any { it.transform.touched }
    }
    EditorTool.Scale -> {
      scaleEnabled
    }
    EditorTool.Adjust -> {
      gradeContributes || sequence.clips.any { it.grade.graded }
    }
    EditorTool.Text -> {
      textEnabled && text.isNotBlank()
    }
    EditorTool.Watermark -> {
      watermarkImage != null
    }
    EditorTool.Stories -> {
      stories.elements.isNotEmpty()
    }
    EditorTool.Audio -> {
      audioSpec != AudioSpec.Keep || sequence.clips.any { it.audio != AudioLevel.Inherit }
    }
    EditorTool.Tracks -> {
      sequence.tracks.isNotEmpty()
    }
    EditorTool.Background -> {
      fillMode != FillMode.Solid || fillColor != EditState.BLACK || frameMode != FrameMode.Auto
    }
  }

private const val ROW_TOOL_WIDTH = 72
