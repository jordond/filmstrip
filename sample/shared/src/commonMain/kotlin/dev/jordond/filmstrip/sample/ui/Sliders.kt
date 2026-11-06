package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A labelled slider that shows the value it is about to send in coral. Pressing the value resets it to the effect's
 * own default when [onReset] is set. The thumb is square, like a trim handle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SliderRow(
  label: String,
  value: Float,
  valueLabel: String,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  steps: Int = 0,
  enabled: Boolean = true,
  onReset: (() -> Unit)? = null,
) {
  val interaction = remember { MutableInteractionSource() }
  Column(modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      SliderValue(label, valueLabel, enabled, onReset)
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
      enabled = enabled,
      interactionSource = interaction,
      thumb = { SliderThumb(enabled, Modifier.focusRing(interaction)) },
      track = { state -> SliderTrack(state, enabled) },
    )
  }
}

@Composable
private fun SliderValue(
  label: String,
  valueLabel: String,
  enabled: Boolean,
  onReset: (() -> Unit)?,
) {
  val style = MaterialTheme.typography.labelMedium.copy(fontSize = 22.sp)
  if (onReset == null) {
    Text(valueLabel, style = style, color = Tokens.coral)
    return
  }
  val interaction = remember { MutableInteractionSource() }
  val height = controlHeight()
  Box(
    modifier = Modifier
      .focusRing(interaction)
      .defaultMinSize(minWidth = height, minHeight = height)
      .semantics { contentDescription = "Reset $label" }
      .clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interaction,
        indication = null,
        onClick = onReset,
      ),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Text(valueLabel, style = style, color = Tokens.coral)
  }
}

@Composable
private fun SliderThumb(enabled: Boolean, modifier: Modifier = Modifier) {
  Box(
    modifier
      .size(width = 12.dp, height = 26.dp)
      .background(if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
      .border(2.dp, MaterialTheme.colorScheme.background),
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderTrack(state: SliderState, enabled: Boolean) {
  val colors = MaterialTheme.colorScheme
  val fill = if (enabled) colors.primary else colors.outline
  val rest = colors.outlineVariant
  Box(
    Modifier
      .fillMaxWidth()
      .height(6.dp)
      .background(rest)
      .drawBehind {
        val span = state.valueRange.endInclusive - state.valueRange.start
        val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
        drawRect(fill, size = Size(size.width * fraction, size.height))
      },
  )
}

/**
 * A two ended slider with the same square thumbs and track as [SliderRow], for a range of the source or the frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun SampleRangeSlider(
  value: ClosedFloatingPointRange<Float>,
  onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
) {
  val start = remember { MutableInteractionSource() }
  val end = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  RangeSlider(
    value = value,
    onValueChange = onValueChange,
    valueRange = valueRange,
    modifier = modifier,
    startInteractionSource = start,
    endInteractionSource = end,
    startThumb = { SliderThumb(enabled = true, Modifier.focusRing(start)) },
    endThumb = { SliderThumb(enabled = true, Modifier.focusRing(end)) },
    track = { state ->
      Box(
        Modifier
          .fillMaxWidth()
          .height(6.dp)
          .background(colors.outlineVariant)
          .drawBehind {
            val span = state.valueRange.endInclusive - state.valueRange.start
            if (span > 0f) {
              val from = (state.activeRangeStart - state.valueRange.start) / span * size.width
              val to = (state.activeRangeEnd - state.valueRange.start) / span * size.width
              drawRect(colors.primary, topLeft = Offset(from, 0f), size = Size(to - from, size.height))
            }
          },
      )
    },
  )
}
