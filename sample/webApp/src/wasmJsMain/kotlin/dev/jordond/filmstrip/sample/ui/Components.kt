package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      trailing?.invoke()
    }
    content()
  }
}

/**
 * A row of chips, one of which is selected. The sample's only picker, so every choice in the editor
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
 * What a [Chip] paints with on a ground that isn't the theme's, such as the blue setup menu.
 *
 * A chip under one of these keeps its selection on show while disabled.
 *
 * @property selectedFill The fill and edge of a selected chip.
 * @property selectedInk The word on a selected chip.
 * @property border The edge of any other chip.
 * @property ink The word on any other chip.
 */
public class ChipPalette(
  public val selectedFill: Color,
  public val selectedInk: Color,
  public val border: Color,
  public val ink: Color,
)

/**
 * The [ChipPalette] chips below this point paint with, or null for the theme's amber.
 */
public val LocalChipPalette: ProvidableCompositionLocal<ChipPalette?> = staticCompositionLocalOf { null }

/**
 * One square key in a [ChipGroup], also usable on its own as a toggle. It fills amber while selected and grows to
 * the touch size on a phone.
 */
@Composable
public fun Chip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  role: Role = Role.RadioButton,
) {
  val interaction = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  val palette = LocalChipPalette.current
  val height = controlHeight()
  val (fill, border, ink) =
    when {
      palette != null && selected -> Triple(palette.selectedFill, palette.selectedFill, palette.selectedInk)
      palette != null -> Triple(Color.Transparent, palette.border, palette.ink)
      !enabled -> Triple(Color.Transparent, colors.outline, colors.onSurfaceVariant)
      selected -> Triple(colors.primary, colors.primary, colors.onPrimary)
      else -> Triple(Color.Transparent, colors.outline, colors.onSurface)
    }
  Box(
    modifier =
      modifier
        .focusRing(interaction)
        .defaultMinSize(minWidth = height, minHeight = height)
        .background(fill)
        .border(2.dp, border)
        .selectable(
          selected = selected,
          enabled = enabled,
          role = role,
          interactionSource = interaction,
          indication = null,
          onClick = onClick,
        ).padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = ink)
  }
}

/**
 * A switch with a label and an optional line of supporting text. The control is an LED beside a word that always
 * changes with it.
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
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      if (supporting != null) {
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    LedSwitch(label, checked, onCheckedChange, enabled = enabled)
  }
}

/**
 * An on or off control made of an LED and the word ON or OFF. It reports itself as a switch named [label].
 */
@Composable
public fun LedSwitch(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val interaction = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  val lit = checked && enabled
  Row(
    modifier =
      modifier
        .focusRing(interaction)
        .defaultMinSize(minHeight = controlHeight())
        .background(colors.surfaceContainer)
        .border(2.dp, if (lit) colors.secondary else colors.outline)
        .semantics { contentDescription = label }
        .toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Switch,
          interactionSource = interaction,
          indication = null,
          onValueChange = onCheckedChange,
        ).padding(horizontal = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Led(if (lit) LedState.Ready else LedState.Off)
    Text(
      text = if (checked) "ON" else "OFF",
      style = MaterialTheme.typography.labelMedium,
      color = if (lit) colors.secondary else colors.onSurfaceVariant,
    )
  }
}

/**
 * A key and its value, for the read-only facts filmstrip hands back. A value holding a number reads in coral unless
 * [valueColor] says otherwise.
 */
@Composable
public fun StatRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  valueColor: Color = if (value.any(Char::isDigit)) Tokens.coral else MaterialTheme.colorScheme.onSurface,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
 * A small outlined label for a fact, used for parity and anything else with a few states and no room. It is not
 * pressable. A plain fact passes [borderColor] as the quiet line and keeps a readable [color].
 */
@Composable
public fun Pill(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
  borderColor: Color = color,
) {
  Box(
    modifier =
      modifier
        .border(2.dp, borderColor)
        .padding(horizontal = 10.dp, vertical = 1.dp),
  ) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
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
  val height = if (isCompactWidth()) Tokens.sizeTouch else 28.dp
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    positions.forEach { y ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        positions.forEach { x ->
          AnchorCell(
            selected = selectedX == x && selectedY == y,
            height = height,
            onClick = { onSelect(x, y) },
          )
        }
      }
    }
  }
}

@Composable
private fun AnchorCell(
  selected: Boolean,
  height: Dp,
  onClick: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      Modifier
        .focusRing(interaction)
        .size(width = maxOf(height, 36.dp), height = height)
        .background(if (selected) colors.primary else colors.surfaceContainer)
        .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline)
        .selectable(
          selected = selected,
          role = Role.RadioButton,
          interactionSource = interaction,
          indication = null,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier
        .size(if (selected) 8.dp else 4.dp)
        .background(if (selected) colors.onPrimary else colors.onSurfaceVariant),
    )
  }
}

/**
 * A row of color swatches, with an optional "none" well for a nullable color.
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
  val interaction = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      Modifier
        .focusRing(interaction)
        .size(controlHeight())
        .background(color ?: colors.surfaceContainer)
        .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outline)
        .selectable(
          selected = selected,
          role = Role.RadioButton,
          interactionSource = interaction,
          indication = null,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    if (color == null) {
      Icon(
        SampleIcons.Close,
        contentDescription = "None",
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

/**
 * The smallest height of a control, the chip size where there is a pointer and the touch size on a phone.
 */
@Composable
internal fun controlHeight(): Dp = if (isCompactWidth()) Tokens.sizeTouch else Tokens.sizeChip
