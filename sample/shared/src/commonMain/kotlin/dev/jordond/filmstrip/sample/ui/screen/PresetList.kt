package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.formatBytes

private const val BLURB =
  "Each one is here for something an encoder has to get right. Downloaded once, then cached. " +
    "Naming one in a bug report gives anybody the same input you had."

/**
 * The clips a session can start from without a file on the device.
 */
@Composable
internal fun PresetList(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val busy = state.loadingPreset != null

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(BLURB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    samplePresets.forEach { preset ->
      PresetRow(
        preset = preset,
        loading = state.loadingPreset == preset,
        enabled = !busy,
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
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(12.dp)

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .clickable(enabled = enabled, onClick = onClick)
      .background(MaterialTheme.colorScheme.surfaceContainer, shape)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(preset.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
      if (loading) {
        CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          color = MaterialTheme.colorScheme.primary,
          strokeWidth = 2.dp,
        )
      }
    }

    Text(preset.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    preset.attribution?.let { attribution ->
      Text(attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Pill(preset.duration.asClock(), MaterialTheme.colorScheme.secondary)
      Pill(formatBytes(preset.bytes), MaterialTheme.colorScheme.outline)
    }
  }
}
