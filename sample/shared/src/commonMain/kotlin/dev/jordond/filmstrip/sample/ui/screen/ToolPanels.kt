package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.effects.Watermark
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.sample.AudioMode
import dev.jordond.filmstrip.sample.CropMode
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.EditorTool
import dev.jordond.filmstrip.sample.FillMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.toImageSource
import dev.jordond.filmstrip.sample.ui.AnchorGrid
import dev.jordond.filmstrip.sample.ui.Chip
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.ControlGroup
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwatchRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.formatFraction
import dev.jordond.filmstrip.sample.ui.formatPercent
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlin.time.Duration.Companion.seconds

private class ToolSpec(
  val tool: EditorTool,
  val label: String,
  val icon: ImageVector,
)

private val Tools = listOf(
  ToolSpec(EditorTool.Trim, "Trim", SampleIcons.Trim),
  ToolSpec(EditorTool.Crop, "Crop", SampleIcons.Crop),
  ToolSpec(EditorTool.Transform, "Transform", SampleIcons.Rotate),
  ToolSpec(EditorTool.Scale, "Scale", SampleIcons.Scale),
  ToolSpec(EditorTool.Adjust, "Adjust", SampleIcons.Brightness),
  ToolSpec(EditorTool.Text, "Text", SampleIcons.TextGlyph),
  ToolSpec(EditorTool.Watermark, "Overlay", SampleIcons.Watermark),
  ToolSpec(EditorTool.Audio, "Audio", SampleIcons.Volume),
  ToolSpec(EditorTool.Background, "Fill", SampleIcons.Layers),
)

/**
 * The tool switcher. A dot marks a tool that is contributing something to the composition right now.
 *
 * @param vertical Lay the tools out in a navigation rail down the side of the window, rather than a
 *   scrolling row under the timeline.
 */
@Composable
public fun ToolRail(
  state: SampleAppState,
  modifier: Modifier = Modifier,
  vertical: Boolean = false,
) {
  if (vertical) {
    NavigationRail(
      modifier = modifier.verticalScroll(rememberScrollState()),
      containerColor = MaterialTheme.colorScheme.surface,
    ) {
      Tools.forEach { spec ->
        NavigationRailItem(
          selected = state.activeTool == spec.tool,
          onClick = { state.activeTool = spec.tool },
          icon = { ToolIcon(spec, marked = state.edit.contributes(spec.tool)) },
          label = { Text(spec.label, style = MaterialTheme.typography.labelSmall) },
        )
      }
    }
  } else {
    Row(
      modifier = modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Tools.forEach { spec ->
        ToolButton(
          spec = spec,
          selected = state.activeTool == spec.tool,
          marked = state.edit.contributes(spec.tool),
          onClick = { state.activeTool = spec.tool },
        )
      }
    }
  }
}

@Composable
private fun ToolIcon(
  spec: ToolSpec,
  marked: Boolean,
) {
  Box {
    Icon(spec.icon, contentDescription = spec.label, modifier = Modifier.size(22.dp))
    if (marked) {
      Box(
        Modifier
          .align(Alignment.TopEnd)
          .size(6.dp)
          .background(MaterialTheme.colorScheme.tertiary, CircleShape),
      )
    }
  }
}

@Composable
private fun ToolButton(
  spec: ToolSpec,
  selected: Boolean,
  marked: Boolean,
  onClick: () -> Unit,
) {
  val tint = when {
    selected -> MaterialTheme.colorScheme.primary
    marked -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.outline
  }

  Column(
    modifier = Modifier
      .background(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        RoundedCornerShape(12.dp),
      ).clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box {
      Icon(spec.icon, contentDescription = spec.label, tint = tint, modifier = Modifier.size(22.dp))
      if (marked) {
        Box(
          Modifier
            .align(Alignment.TopEnd)
            .size(6.dp)
            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
        )
      }
    }
    Text(spec.label, style = MaterialTheme.typography.labelSmall, color = tint)
  }
}

/**
 * The controls for whichever tool is selected.
 */
@Composable
public fun ToolPanel(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    when (state.activeTool) {
      EditorTool.Trim -> TrimPanel(state)
      EditorTool.Crop -> CropPanel(state)
      EditorTool.Transform -> TransformPanel(state)
      EditorTool.Scale -> ScalePanel(state)
      EditorTool.Adjust -> AdjustPanel(state)
      EditorTool.Text -> TextPanel(state)
      EditorTool.Watermark -> WatermarkPanel(state)
      EditorTool.Audio -> AudioPanel(state)
      EditorTool.Background -> BackgroundPanel(state)
    }
  }
}

@Composable
private fun TrimPanel(state: SampleAppState) {
  val edit = state.edit
  val duration = state.sourceDuration?.let { it.inWholeMilliseconds / 1000f } ?: 0f

  ControlGroup("Clip") {
    SwitchRow(
      label = "Trim",
      supporting = "Keeps one range of the source",
      checked = edit.trimEnabled,
      onCheckedChange = {
        edit.trimEnabled = it
        if (it && edit.trimEndSeconds <= edit.trimStartSeconds) edit.trimEndSeconds = duration
        state.onEditChanged()
      },
    )

    if (edit.trimEnabled && duration > 0f) {
      RangeSlider(
        value = edit.trimStartSeconds..edit.trimEndSeconds,
        onValueChange = { range ->
          edit.trimStartSeconds = range.start
          edit.trimEndSeconds = range.endInclusive
          state.onEditChanged()
        },
        valueRange = 0f..duration,
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
          edit.trimStartSeconds.toDouble().seconds.asClock(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "${state.editedDuration.asClock()} kept",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          edit.trimEndSeconds.toDouble().seconds.asClock(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  ControlGroup("Clip audio") {
    SwitchRow(
      label = "Mute this clip",
      supporting = "AudioLevel.Mute, applied to the clip rather than the whole edit",
      checked = edit.clipMuted,
      onCheckedChange = {
        edit.clipMuted = it
        state.onEditChanged()
      },
    )
  }
}

@Composable
private fun CropPanel(state: SampleAppState) {
  val edit = state.edit

  ControlGroup("Reframe") {
    ChipGroup(
      options = listOf("Off" to CropMode.Off, "Aspect" to CropMode.Aspect, "Rectangle" to CropMode.Rect),
      selected = edit.cropMode,
      onSelect = {
        edit.cropMode = it
        state.onEditChanged()
      },
    )
  }

  when (edit.cropMode) {
    CropMode.Off -> Unit

    CropMode.Aspect -> {
      ControlGroup("Aspect ratio") {
        ChipGroup(
          options = listOf(
            "9:16" to AspectRatio.Portrait,
            "1:1" to AspectRatio.Square,
            "4:5" to AspectRatio.Feed,
            "4:3" to AspectRatio.Classic,
            "16:9" to AspectRatio.Landscape,
            "2.39:1" to AspectRatio.Cinema,
          ),
          selected = edit.cropAspect,
          onSelect = {
            edit.cropAspect = it
            state.onEditChanged()
          },
        )
      }
      ControlGroup("Fit") {
        ChipGroup(
          options = listOf("Crop" to Fit.Crop, "Contain" to Fit.Contain, "Stretch" to Fit.Stretch),
          selected = edit.cropFit,
          onSelect = {
            edit.cropFit = it
            state.onEditChanged()
          },
        )
      }
      if (edit.cropFit == Fit.Crop) {
        ControlGroup("Anchor") {
          AnchorGrid(
            selectedX = edit.cropAnchorX,
            selectedY = edit.cropAnchorY,
            onSelect = { x, y ->
              edit.cropAnchorX = x
              edit.cropAnchorY = y
              state.onEditChanged()
            },
          )
        }
      }
    }

    CropMode.Rect -> {
      ControlGroup("Rectangle") {
        Text(
          "Normalised to the frame the rotation produced, origin top left.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
        RangeSliderRow(
          label = "Horizontal",
          start = edit.cropLeft,
          end = edit.cropRight,
          onChange = { start, end ->
            edit.cropLeft = start
            edit.cropRight = end
            state.onEditChanged()
          },
        )
        RangeSliderRow(
          label = "Vertical",
          start = edit.cropTop,
          end = edit.cropBottom,
          onChange = { start, end ->
            edit.cropTop = start
            edit.cropBottom = end
            state.onEditChanged()
          },
        )
      }
    }
  }
}

@Composable
private fun RangeSliderRow(
  label: String,
  start: Float,
  end: Float,
  onChange: (Float, Float) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        "${formatFraction(start)} to ${formatFraction(end)}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    RangeSlider(
      value = start..end,
      onValueChange = { range ->
        onChange(
          range.start.coerceIn(0f, range.endInclusive - MIN_RECT),
          range.endInclusive.coerceIn(range.start + MIN_RECT, 1f),
        )
      },
      valueRange = 0f..1f,
    )
  }
}

@Composable
private fun TransformPanel(state: SampleAppState) {
  val edit = state.edit

  ControlGroup("Rotate") {
    Text(
      "Counter-clockwise, baked into the pixels rather than written as container metadata.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    ChipGroup(
      options = listOf("0" to 0, "90" to 90, "180" to 180, "270" to 270),
      selected = edit.rotationDegrees,
      onSelect = {
        edit.rotationDegrees = it
        state.onEditChanged()
      },
    )
  }

  ControlGroup("Mirror") {
    SwitchRow(
      label = "Flip horizontally",
      checked = edit.flipHorizontal,
      onCheckedChange = {
        edit.flipHorizontal = it
        state.onEditChanged()
      },
    )
    SwitchRow(
      label = "Flip vertically",
      checked = edit.flipVertical,
      onCheckedChange = {
        edit.flipVertical = it
        state.onEditChanged()
      },
    )
  }
}

@Composable
private fun ScalePanel(state: SampleAppState) {
  val edit = state.edit

  ControlGroup("Scale") {
    SwitchRow(
      label = "Set output height",
      supporting = "A composition effect, separate from the export spec's own target height",
      checked = edit.scaleEnabled,
      onCheckedChange = {
        edit.scaleEnabled = it
        state.onEditChanged()
      },
    )

    if (edit.scaleEnabled) {
      ChipGroup(
        options = listOf("360" to 360, "480" to 480, "720" to 720, "1080" to 1080, "1440" to 1440, "2160" to 2160),
        selected = edit.scaleHeight,
        onSelect = {
          edit.scaleHeight = it
          state.onEditChanged()
        },
      )
      ChipGroup(
        options = listOf("Contain" to Fit.Contain, "Crop" to Fit.Crop, "Stretch" to Fit.Stretch),
        selected = edit.scaleFit,
        onSelect = {
          edit.scaleFit = it
          state.onEditChanged()
        },
      )
    }
  }
}

@Composable
private fun AdjustPanel(state: SampleAppState) {
  val edit = state.edit

  ControlGroup("Brightness") {
    Text(
      "Multiplies every colour channel. Black stays black, and a factor above 1 brightens until a " +
          "channel saturates.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    SliderRow(
      label = "Factor",
      value = edit.brightness,
      valueLabel = formatPercent(edit.brightness),
      range = 0f..2f,
      onValueChange = {
        edit.brightness = it
        state.onEditChanged()
      },
      onReset = {
        edit.brightness = 1f
        state.onEditChanged()
      },
    )
  }
}

@Composable
private fun TextPanel(state: SampleAppState) {
  val edit = state.edit
  val duration = state.editedDurationSeconds

  ControlGroup("Burned-in text") {
    SwitchRow(
      label = "Draw text",
      checked = edit.textEnabled,
      onCheckedChange = {
        edit.textEnabled = it
        state.onEditChanged()
      },
    )
    OutlinedTextField(
      value = edit.text,
      onValueChange = {
        edit.text = it
        state.onEditChanged()
      },
      label = { Text("Caption") },
      enabled = edit.textEnabled,
      modifier = Modifier.fillMaxWidth(),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
      ),
    )
  }

  if (!edit.textEnabled) return

  ControlGroup("Style") {
    SliderRow(
      label = "Size",
      value = edit.textSize,
      valueLabel = formatPercent(edit.textSize),
      range = 0.02f..0.2f,
      onValueChange = {
        edit.textSize = it
        state.onEditChanged()
      },
      onReset = {
        edit.textSize = 0.06f
        state.onEditChanged()
      },
    )
    ChipGroup(
      options = listOf(
        "Regular" to FontWeight.Regular,
        "Medium" to FontWeight.Medium,
        "Bold" to FontWeight.Bold,
      ),
      selected = edit.textWeight,
      onSelect = {
        edit.textWeight = it
        state.onEditChanged()
      },
    )
    ChipGroup(
      options = listOf(
        "Start" to TextAlignment.Start,
        "Center" to TextAlignment.Center,
        "End" to TextAlignment.End,
      ),
      selected = edit.textAlignment,
      onSelect = {
        edit.textAlignment = it
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Wrap width",
      value = edit.textMaxWidth,
      valueLabel = formatPercent(edit.textMaxWidth),
      range = 0.2f..1f,
      onValueChange = {
        edit.textMaxWidth = it
        state.onEditChanged()
      },
    )
  }

  ControlGroup("Colour") {
    SwatchRow(
      colors = Palette,
      selected = edit.textColor,
      onSelect = {
        edit.textColor = it ?: EditState.WHITE
        state.onEditChanged()
      },
    )
  }

  ControlGroup("Plate behind the text") {
    SwatchRow(
      colors = Plates,
      selected = edit.textPlate,
      onSelect = {
        edit.textPlate = it
        state.onEditChanged()
      },
      includeNone = true,
    )
  }

  ControlGroup("Anchor") {
    AnchorGrid(
      selectedX = edit.textAnchorX,
      selectedY = edit.textAnchorY,
      onSelect = { x, y ->
        edit.textAnchorX = x
        edit.textAnchorY = y
        state.onEditChanged()
      },
    )
  }

  ControlGroup("Visible during") {
    SwitchRow(
      label = "Only part of the edit",
      checked = edit.textTimed,
      onCheckedChange = {
        edit.textTimed = it
        if (it && edit.textEndSeconds > duration) edit.textEndSeconds = duration
        state.onEditChanged()
      },
    )
    if (edit.textTimed && duration > 0f) {
      WindowSlider(
        start = edit.textStartSeconds,
        end = edit.textEndSeconds,
        duration = duration,
        onChange = { start, end ->
          edit.textStartSeconds = start
          edit.textEndSeconds = end
          state.onEditChanged()
        },
      )
    }
  }
}

@Composable
private fun WatermarkPanel(state: SampleAppState) {
  val edit = state.edit
  val duration = state.editedDurationSeconds
  val picker = rememberFilePickerLauncher(
    type = FileKitType.Image,
    onResult = { file ->
      if (file != null) {
        edit.watermarkImage = file.toImageSource()
        edit.watermarkLabel = file.name
        state.onEditChanged()
      }
    },
  )

  ControlGroup("Image") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Chip(
        label = if (edit.watermarkImage == null) "Pick an image" else "Replace",
        selected = false,
        onClick = picker::launch,
      )
      if (edit.watermarkImage != null) {
        Chip(
          label = "Remove",
          selected = false,
          onClick = {
            edit.watermarkImage = null
            edit.watermarkLabel = ""
            state.onEditChanged()
          },
        )
      }
    }
    if (edit.watermarkLabel.isNotBlank()) {
      Text(edit.watermarkLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
  }

  if (edit.watermarkImage == null) return

  ControlGroup("Corner") {
    ChipGroup(
      options = listOf(
        "Top left" to Corner.TopStart,
        "Top right" to Corner.TopEnd,
        "Bottom left" to Corner.BottomStart,
        "Bottom right" to Corner.BottomEnd,
      ),
      selected = edit.watermarkCorner,
      onSelect = {
        edit.watermarkCorner = it
        state.onEditChanged()
      },
    )
  }

  ControlGroup("Placement") {
    SliderRow(
      label = "Margin",
      value = edit.watermarkMargin,
      valueLabel = formatPercent(edit.watermarkMargin),
      range = 0f..0.25f,
      onValueChange = {
        edit.watermarkMargin = it
        state.onEditChanged()
      },
      onReset = {
        edit.watermarkMargin = Watermark.DEFAULT_MARGIN
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Width",
      value = edit.watermarkScale,
      valueLabel = formatPercent(edit.watermarkScale),
      range = 0.05f..0.6f,
      onValueChange = {
        edit.watermarkScale = it
        state.onEditChanged()
      },
      onReset = {
        edit.watermarkScale = Watermark.DEFAULT_SCALE
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Opacity",
      value = edit.watermarkOpacity,
      valueLabel = formatPercent(edit.watermarkOpacity),
      range = 0f..1f,
      onValueChange = {
        edit.watermarkOpacity = it
        state.onEditChanged()
      },
    )
  }

  ControlGroup("Visible during") {
    SwitchRow(
      label = "Only part of the edit",
      checked = edit.watermarkTimed,
      onCheckedChange = {
        edit.watermarkTimed = it
        if (it && edit.watermarkEndSeconds > duration) edit.watermarkEndSeconds = duration
        state.onEditChanged()
      },
    )
    if (edit.watermarkTimed && duration > 0f) {
      WindowSlider(
        start = edit.watermarkStartSeconds,
        end = edit.watermarkEndSeconds,
        duration = duration,
        onChange = { start, end ->
          edit.watermarkStartSeconds = start
          edit.watermarkEndSeconds = end
          state.onEditChanged()
        },
      )
    }
  }
}

@Composable
private fun WindowSlider(
  start: Float,
  end: Float,
  duration: Float,
  onChange: (Float, Float) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    RangeSlider(
      value = start.coerceIn(0f, duration)..end.coerceIn(0f, duration),
      onValueChange = { range -> onChange(range.start, range.endInclusive) },
      valueRange = 0f..duration,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        start.toDouble().seconds.asClock(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        end.toDouble().seconds.asClock(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun AudioPanel(state: SampleAppState) {
  val edit = state.edit

  ControlGroup("Mixed audio") {
    Text(
      "Set on the composition, after every track is mixed. Mute keeps a silent track, remove writes " +
          "none at all.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    ChipGroup(
      options = listOf(
        "Keep" to AudioMode.Keep,
        "Volume" to AudioMode.Volume,
        "Mute" to AudioMode.Mute,
        "Remove" to AudioMode.Remove,
        "Audio only" to AudioMode.AudioOnly,
      ),
      selected = edit.audioMode,
      onSelect = {
        edit.audioMode = it
        state.onEditChanged()
      },
    )
    if (edit.audioMode == AudioMode.Volume) {
      SliderRow(
        label = "Gain",
        value = edit.audioGain,
        valueLabel = formatPercent(edit.audioGain),
        range = 0f..2f,
        onValueChange = {
          edit.audioGain = it
          state.onEditChanged()
        },
        onReset = {
          edit.audioGain = 1f
          state.onEditChanged()
        },
      )
    }
  }

  ControlGroup("Clip") {
    SwitchRow(
      label = "Mute this clip",
      supporting = "Levels multiply down the scopes, so a mute here silences the clip alone",
      checked = edit.clipMuted,
      onCheckedChange = {
        edit.clipMuted = it
        state.onEditChanged()
      },
    )
  }
}

@Composable
private fun BackgroundPanel(state: SampleAppState) {
  val edit = state.edit

  ControlGroup("Fill") {
    Text(
      "What lands where no clip's pixels do: letterbox bars, gaps between tracks, and rounding at " +
          "the edges.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.outline,
    )
    ChipGroup(
      options = listOf("Solid" to FillMode.Solid, "Blurred" to FillMode.Blurred),
      selected = edit.fillMode,
      onSelect = {
        edit.fillMode = it
        state.onEditChanged()
      },
    )
  }

  when (edit.fillMode) {
    FillMode.Solid -> ControlGroup("Colour") {
      SwatchRow(
        colors = Plates,
        selected = edit.fillColor,
        onSelect = {
          edit.fillColor = it ?: EditState.BLACK
          state.onEditChanged()
        },
      )
    }

    FillMode.Blurred -> ControlGroup("Blur") {
      SliderRow(
        label = "Radius",
        value = edit.blurRadius,
        valueLabel = formatPercent(edit.blurRadius),
        range = 0f..0.2f,
        onValueChange = {
          edit.blurRadius = it
          state.onEditChanged()
        },
        onReset = {
          edit.blurRadius = 0.04f
          state.onEditChanged()
        },
      )
      SliderRow(
        label = "Dim",
        value = edit.blurDim,
        valueLabel = formatPercent(edit.blurDim),
        range = 0f..1f,
        onValueChange = {
          edit.blurDim = it
          state.onEditChanged()
        },
      )
    }
  }
}

/**
 * Whether a tool is putting anything into the composition, which is what marks it on the rail.
 */
private fun EditState.contributes(tool: EditorTool): Boolean =
  when (tool) {
    EditorTool.Trim -> trimEnabled
    EditorTool.Crop -> cropMode != CropMode.Off
    EditorTool.Transform -> rotationDegrees != 0 || flipHorizontal || flipVertical
    EditorTool.Scale -> scaleEnabled
    EditorTool.Adjust -> brightness != 1f
    EditorTool.Text -> textEnabled && text.isNotBlank()
    EditorTool.Watermark -> watermarkImage != null
    EditorTool.Audio -> audioSpec != AudioSpec.Keep || clipMuted
    EditorTool.Background -> fillMode != FillMode.Solid || fillColor != EditState.BLACK
  }

private val Palette = listOf(
  0xFFFFFFFF.toInt(),
  0xFF000000.toInt(),
  0xFFFFB454.toInt(),
  0xFF5FD3A3.toInt(),
  0xFF8E9BFF.toInt(),
  0xFFFF6F6F.toInt(),
)

private val Plates = listOf(
  0xFF000000.toInt(),
  0xFF1A1A21.toInt(),
  0xFFFFFFFF.toInt(),
  0xFFFFB454.toInt(),
  0xFF8E9BFF.toInt(),
  0xFF5FD3A3.toInt(),
)

private const val MIN_RECT = 0.05f
