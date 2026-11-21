package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.sample.CropMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.AnchorGrid
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SampleRangeSlider
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.formatFraction

@Composable
internal fun CropPanel(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Reframe") {
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
    CropMode.Off -> {}
    CropMode.Aspect -> {
      ToolSection("Aspect ratio") {
        ChipGroup(
          options =
            listOf(
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
      ToolSection("Fit") {
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
        ToolSection("Anchor") {
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
      ToolSection("Lock") {
        Text(
          "Drag the rectangle on the preview. A lock holds the shape the handles may produce.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipGroup(
          options =
            listOf(
              "Free" to null,
              "9:16" to AspectRatio.Portrait,
              "1:1" to AspectRatio.Square,
              "4:5" to AspectRatio.Feed,
              "16:9" to AspectRatio.Landscape,
            ),
          selected = edit.cropLockAspect,
          onSelect = {
            edit.cropLockAspect = it
            state.onEditChanged()
          },
        )
      }
      ToolSection("Rectangle") {
        Text(
          "Normalised to the frame the rotation produced, origin top left.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 22.sp),
        color = Tokens.coral,
      )
    }
    SampleRangeSlider(
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

private const val MIN_RECT = 0.05f
