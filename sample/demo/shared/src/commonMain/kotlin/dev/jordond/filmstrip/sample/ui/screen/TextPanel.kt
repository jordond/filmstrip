package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.AnchorGrid
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwatchRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.formatPercent
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment

@Composable
internal fun TextPanel(state: SampleAppState) {
  val edit = state.edit
  val duration = state.editedDurationSeconds

  ToolSection("Burned-in text") {
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
      colors =
        OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
          focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
  }

  if (!edit.textEnabled) return

  ToolSection("Style") {
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
      options =
        listOf(
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
      options =
        listOf(
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

  ToolSection("Color") {
    SwatchRow(
      colors = Palette,
      selected = edit.textColor,
      onSelect = {
        edit.textColor = it ?: EditState.WHITE
        state.onEditChanged()
      },
    )
  }

  ToolSection("Plate behind the text") {
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

  ToolSection("Anchor") {
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

  ToolSection("Visible during") {
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

  MotionSection(state, edit.textMotion, timed = edit.textTimed)
}
