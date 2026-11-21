package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.filekit.compose.rememberImagePickerLauncher
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.Chip
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.formatPercent
import io.github.vinceglb.filekit.name
import kotlin.math.roundToInt

@Composable
internal fun WatermarkPanel(state: SampleAppState) {
  val edit = state.edit
  val duration = state.editedDurationSeconds
  val picker =
    rememberImagePickerLauncher { picked ->
      if (picked != null) {
        edit.watermarkImage = picked.source
        edit.watermarkLabel = picked.file.name
        state.onEditChanged()
      }
    }

  ToolSection("Image") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Chip(
        label = if (edit.watermarkImage == null) "Pick an image" else "Replace",
        selected = false,
        role = Role.Button,
        onClick = picker::launch,
      )
      if (edit.watermarkImage != null) {
        Chip(
          label = "Remove",
          selected = false,
          role = Role.Button,
          onClick = {
            edit.watermarkImage = null
            edit.watermarkLabel = ""
            state.onEditChanged()
          },
        )
      }
    }
    if (edit.watermarkLabel.isNotBlank()) {
      Text(
        text = edit.watermarkLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  if (edit.watermarkImage == null) return

  ToolSection("Corner") {
    ChipGroup(
      options =
        listOf(
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

  ToolSection("Placement") {
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
        edit.watermarkMargin = ImageOverlay.DEFAULT_MARGIN
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
        edit.watermarkScale = ImageOverlay.DEFAULT_SCALE
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

  ToolSection("Visible during") {
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

  MotionSection(state, edit.watermarkMotion, timed = edit.watermarkTimed)

  ToolSection("Sprite sheet") {
    SwitchRow(
      label = "The image is a grid of frames",
      checked = edit.watermarkSheetEnabled,
      onCheckedChange = {
        edit.watermarkSheetEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.watermarkSheetEnabled) {
      Text("Columns", style = MaterialTheme.typography.labelMedium)
      ChipGroup(
        options = SHEET_COLUMNS.map { it.toString() to it },
        selected = edit.watermarkSheetColumns,
        onSelect = {
          edit.watermarkSheetColumns = it
          state.onEditChanged()
        },
      )
      Text("Rows", style = MaterialTheme.typography.labelMedium)
      ChipGroup(
        options = SHEET_ROWS.map { it.toString() to it },
        selected = edit.watermarkSheetRows,
        onSelect = {
          edit.watermarkSheetRows = it
          state.onEditChanged()
        },
      )
      GradeSlider(
        state,
        "Frame time",
        edit.watermarkSheetFrameMillis,
        "${edit.watermarkSheetFrameMillis.roundToInt()} ms",
        20f..500f,
        80f,
      ) {
        edit.watermarkSheetFrameMillis = it
      }
      SwitchRow(
        label = "Play once",
        checked = edit.watermarkSheetOnce,
        onCheckedChange = {
          edit.watermarkSheetOnce = it
          state.onEditChanged()
        },
      )
    }
  }
}

private val SHEET_COLUMNS = listOf(1, 2, 3, 4, 6, 8)
private val SHEET_ROWS = listOf(1, 2, 3, 4)
