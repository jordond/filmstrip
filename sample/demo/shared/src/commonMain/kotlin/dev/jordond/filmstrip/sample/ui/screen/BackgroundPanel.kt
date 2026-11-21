package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.FillMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwatchRow
import dev.jordond.filmstrip.sample.ui.formatPercent

@Composable
internal fun BackgroundPanel(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Fill") {
    Text(
      "What lands where no clip's pixels do: letterbox bars, gaps between tracks, and rounding at " +
        "the edges.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    FillMode.Solid -> {
      ToolSection("Color") {
        SwatchRow(
          colors = Plates,
          selected = edit.fillColor,
          onSelect = {
            edit.fillColor = it ?: EditState.BLACK
            state.onEditChanged()
          },
        )
      }
    }
    FillMode.Blurred -> {
      ToolSection("Blur") {
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

  FrameSections(state)
  PreviewSections(state)
}
