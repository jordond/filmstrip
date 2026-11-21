package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.asSeconds
import dev.jordond.filmstrip.sample.ui.SampleRangeSlider
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.excludeBackGesture

@Composable
internal fun TrimPanel(state: SampleAppState) {
  val edit = state.edit
  val duration = state.sourceDuration?.let { it.inWholeMilliseconds / 1000f } ?: 0f

  ToolSection("Clip") {
    SwitchRow(
      label = "Trim",
      supporting = "Keeps one range of the source",
      checked = edit.trimEnabled,
      onCheckedChange = {
        edit.trimEnabled = it
        if (it && edit.trimEndSeconds <= edit.trimStartSeconds) edit.trimEndSeconds = duration
        state.onEditChanged()
      },
    )

    if (edit.trimEnabled && duration > 0f) {
      SampleRangeSlider(
        value = edit.trimStartSeconds..edit.trimEndSeconds,
        onValueChange = { range ->
          edit.trimStartSeconds = range.start
          edit.trimEndSeconds = range.endInclusive
          state.onEditChanged()
        },
        valueRange = 0f..duration,
        modifier = Modifier.excludeBackGesture(),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
          edit.trimStartSeconds
            .asSeconds()
            .asClock(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "${state.editedDuration.asClock()} kept",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          edit.trimEndSeconds
            .asSeconds()
            .asClock(),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  ToolSection("Clip audio") {
    SwitchRow(
      label = "Mute this clip",
      supporting = "AudioLevel.Mute, applied to the clip rather than the whole edit",
      checked = edit.clipMuted,
      onCheckedChange = {
        edit.clipMuted = it
        state.onEditChanged()
      },
    )
  }
}
