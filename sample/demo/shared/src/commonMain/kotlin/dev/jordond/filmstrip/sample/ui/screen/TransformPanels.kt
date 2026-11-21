package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.transformInScope
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SwitchRow

@Composable
internal fun TransformPanel(state: SampleAppState) {
  val transform = state.transformInScope

  ScopeSwitch(state)

  ToolSection("Rotate") {
    Text(
      "Counter-clockwise, baked into the pixels rather than written as container metadata.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChipGroup(
      options = listOf("0" to 0, "90" to 90, "180" to 180, "270" to 270),
      selected = transform.rotationDegrees,
      onSelect = {
        transform.rotationDegrees = it
        state.onEditChanged()
      },
    )
  }

  ToolSection("Mirror") {
    SwitchRow(
      label = "Flip horizontally",
      checked = transform.flipHorizontal,
      onCheckedChange = {
        transform.flipHorizontal = it
        state.onEditChanged()
      },
    )
    SwitchRow(
      label = "Flip vertically",
      checked = transform.flipVertical,
      onCheckedChange = {
        transform.flipVertical = it
        state.onEditChanged()
      },
    )
  }
}

@Composable
internal fun ScalePanel(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Scale") {
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
