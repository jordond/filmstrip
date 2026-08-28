package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A titled block of controls, which is the unit every tool panel is built out of.
 */
@Composable
public fun ControlGroup(
  label: String,
  modifier: Modifier = Modifier,
  trailing: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
      )
      trailing?.invoke()
    }
    content()
  }
}

/**
 * A row of pills, one of which is selected. The sample's only picker, so every choice in the editor
 * looks and behaves the same whether it holds four options or nine.
 */
@Composable
public fun <T> ChipGroup(
  options: List<Pair<String, T>>,
  selected: T,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    options.forEach { (label, value) ->
      Chip(
        label = label,
        selected = selected == value,
        enabled = enabled,
        onClick = { onSelect(value) },
      )
    }
  }
}

/**
 * One pill in a [ChipGroup], also usable on its own as a toggle.
 */
@Composable
public fun Chip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    enabled = enabled,
    modifier = modifier,
    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
    colors = FilterChipDefaults.filterChipColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
      labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
      selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
      selectedLabelColor = MaterialTheme.colorScheme.primary,
    ),
    border = FilterChipDefaults.filterChipBorder(
      enabled = enabled,
      selected = selected,
      borderColor = MaterialTheme.colorScheme.outlineVariant,
      selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
      selectedBorderWidth = 1.dp,
    ),
  )
}

/**
 * A labelled slider that shows the value it is about to send, plus an optional reset to the
 * effect's own default.
 */
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
  Column(modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (onReset != null) {
          IconButton(onClick = onReset, enabled = enabled, modifier = Modifier.size(20.dp)) {
            Icon(
              imageVector = SampleIcons.Undo,
              contentDescription = "Reset $label",
              tint = MaterialTheme.colorScheme.outline,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
      enabled = enabled,
    )
  }
}

/**
 * A switch with a label and an optional line of supporting text.
 */
@Composable
public fun SwitchRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  supporting: String? = null,
  enabled: Boolean = true,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      if (supporting != null) {
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
      }
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

/**
 * A key and its value, for the read-only facts filmstrip hands back.
 */
@Composable
public fun StatRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.width(16.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      color = valueColor,
      textAlign = TextAlign.End,
      modifier = Modifier.weight(1f, fill = false),
    )
  }
}

/**
 * A small status marker, used for verdicts, parity and anything else with three states and no room.
 */
@Composable
public fun Pill(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .background(color.copy(alpha = 0.14f), CircleShape)
      .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), CircleShape)
      .padding(horizontal = 10.dp, vertical = 4.dp),
  ) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
  }
}

/**
 * The three-by-three grid an anchor is picked from, which is easier to aim at than nine names.
 */
@Composable
public fun AnchorGrid(
  selectedX: Float,
  selectedY: Float,
  onSelect: (Float, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val positions = listOf(0f, 0.5f, 1f)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    positions.forEach { y ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        positions.forEach { x ->
          val selected = selectedX == x && selectedY == y
          Box(
            modifier = Modifier
              .size(width = 34.dp, height = 24.dp)
              .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(6.dp),
              ).border(
                BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(6.dp),
              ).clickable { onSelect(x, y) },
            contentAlignment = Alignment.Center,
          ) {
            Box(
              Modifier
                .size(if (selected) 8.dp else 5.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            )
          }
        }
      }
    }
  }
}

/**
 * A row of colour swatches, with an optional "none" well for a nullable colour.
 */
@Composable
public fun SwatchRow(
  colors: List<Int>,
  selected: Int?,
  onSelect: (Int?) -> Unit,
  modifier: Modifier = Modifier,
  includeNone: Boolean = false,
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (includeNone) {
      Swatch(color = null, selected = selected == null, onClick = { onSelect(null) })
    }
    colors.forEach { argb ->
      Swatch(color = Color(argb), selected = selected == argb, onClick = { onSelect(argb) })
    }
  }
}

@Composable
private fun Swatch(
  color: Color?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(30.dp)
      .background(color ?: MaterialTheme.colorScheme.surfaceContainer, CircleShape)
      .border(
        BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        CircleShape,
      ).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    if (color == null) {
      Icon(SampleIcons.Close, contentDescription = "None", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
    }
  }
}
