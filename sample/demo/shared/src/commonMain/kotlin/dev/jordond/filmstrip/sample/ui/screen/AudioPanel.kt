package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.jordond.filmstrip.sample.AudioMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.formatPercent

@Composable
internal fun AudioPanel(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Mixed audio") {
    Text(
      "Set on the composition, after every track is mixed. Mute keeps a silent track, remove writes " +
        "none at all.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChipGroup(
      options =
        listOf(
          "Keep" to AudioMode.Keep,
          "Volume" to AudioMode.Volume,
          "Mute" to AudioMode.Mute,
          "Remove" to AudioMode.Remove,
          "Audio only" to AudioMode.AudioOnly,
        ),
      selected = edit.audioMode,
      onSelect = {
        edit.audioMode = it
        state.onEditChanged()
      },
    )
    if (edit.audioMode == AudioMode.Volume) {
      SliderRow(
        label = "Gain",
        value = edit.audioGain,
        valueLabel = formatPercent(edit.audioGain),
        range = 0f..2f,
        onValueChange = {
          edit.audioGain = it
          state.onEditChanged()
        },
        onReset = {
          edit.audioGain = 1f
          state.onEditChanged()
        },
      )
    }
  }
}
