package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.Led
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.bottomLine
import dev.jordond.filmstrip.sample.ui.focusRing
import dev.jordond.filmstrip.sample.ui.formatBytes
import dev.jordond.filmstrip.sample.ui.isCompactWidth

private const val BLURB =
  "Each one is here for something an encoder has to get right. Downloaded once, then cached. " +
      "Naming one in a bug report gives anybody the same input you had."

/**
 * The clips a session can start from without a file on the device, a blurb on what they are for and then one tape to a
 * row.
 */
@Composable
internal fun PresetList(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val busy = state.loadingPreset != null

  Column(modifier.fillMaxWidth()) {
    Text(
      text = BLURB,
      style = MaterialTheme.typography.bodySmall,
      color = Tokens.mutedStrong,
      modifier = Modifier
        .fillMaxWidth()
        .bottomLine(MaterialTheme.colorScheme.outlineVariant)
        .padding(horizontal = if (isCompactWidth()) 12.dp else 16.dp, vertical = 10.dp),
    )
    samplePresets.forEachIndexed { index, preset ->
      PresetRow(
        preset = preset,
        loading = state.loadingPreset == preset,
        enabled = !busy,
        last = index == samplePresets.lastIndex,
        onClick = { state.pickPreset(preset) },
      )
    }
  }
}

@Composable
private fun PresetRow(
  preset: SamplePreset,
  loading: Boolean,
  enabled: Boolean,
  last: Boolean,
  onClick: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val colors = MaterialTheme.colorScheme
  val compact = isCompactWidth()
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .focusRing(interaction, inset = 6.dp)
      .defaultMinSize(minHeight = if (compact) 56.dp else 60.dp)
      .background(if (hovered) colors.surfaceContainer else Color.Transparent)
      .then(if (last) Modifier else Modifier.bottomLine(colors.outlineVariant))
      .clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
      ).padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 10.dp),
    horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(preset.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
      Text(preset.detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      preset.attribution?.let { attribution ->
        Text(attribution, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    }
    if (loading) {
      Led(LedState.Busy, blinking = true)
    } else {
      Text(
        text = "${formatBytes(preset.bytes)} · ${preset.duration.asClock()}",
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.coral,
        maxLines = 1,
      )
    }
  }
}
