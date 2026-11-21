package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.filekit.compose.rememberMediaPickerLauncher
import dev.jordond.filmstrip.sample.ClipEdit
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.TrackEdit
import dev.jordond.filmstrip.sample.TrackId
import dev.jordond.filmstrip.sample.clipsDuration
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.Chip
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.formatPercent
import io.github.vinceglb.filekit.name
import kotlin.math.roundToInt

/**
 * The tracks that play alongside the clips of the edit: adding one from a file or from a built-in clip's audio,
 * and where each starts, whether it loops, its fades and its level.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TracksPanel(state: SampleAppState) {
  // The picker outlives the tap that opened it, so the track it is adding to is held until it answers.
  var target by remember { mutableStateOf<TrackId?>(null) }
  val picker =
    rememberMediaPickerLauncher(
      onError = { state.onPickFailed(it.message) },
      onResult = { picked ->
        val source = picked?.source
        if (source != null) {
          val label = picked.file.name
          val track = state.sequence.tracks.firstOrNull { it.id == target }
          if (track != null) state.addToTrack(track, source, label) else state.addTrack(source, label)
        }
      },
    )

  ToolSection("Add a track") {
    Note("An extra track is an audio bed, like music or a voice over. Video plays from the clips of the edit only")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Chip(
        label = "From a file",
        selected = false,
        role = Role.Button,
        onClick = {
          target = null
          picker.launch()
        },
      )
      samplePresets.forEach { preset ->
        Chip(
          label = "Music: ${preset.name}",
          selected = false,
          enabled = state.loadingPreset == null,
          role = Role.Button,
          onClick = { state.addMusic(preset) },
        )
      }
    }
    state.pickFailure?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }

  state.sequence.tracks.forEach { track ->
    TrackSection(
      state = state,
      track = track,
      onAddClip = {
        target = track.id
        picker.launch()
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackSection(
  state: SampleAppState,
  track: TrackEdit,
  onAddClip: () -> Unit,
) {
  ToolSection(track.label) {
    track.clips.forEach { clip -> Note(clip.line()) }

    SliderRow(
      label = "Starts at",
      value = track.startSeconds,
      valueLabel = secondsLabel(track.startSeconds),
      range = 0f..(state.sequence.clipsDuration.inWholeMilliseconds / 1000f).coerceAtLeast(MIN_START_RANGE),
      onValueChange = {
        track.startSeconds = it
        state.onEditChanged()
      },
      onReset = {
        track.startSeconds = 0f
        state.onEditChanged()
      },
    )
    SwitchRow(
      label = "Loop",
      supporting = "Repeats until the edit ends, and adds nothing to its length",
      checked = track.looping,
      onCheckedChange = {
        track.looping = it
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Fade in",
      value = track.fadeInSeconds,
      valueLabel = secondsLabel(track.fadeInSeconds),
      range = 0f..MAX_TRACK_FADE,
      onValueChange = {
        track.fadeInSeconds = it
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Fade out",
      value = track.fadeOutSeconds,
      valueLabel = secondsLabel(track.fadeOutSeconds),
      range = 0f..MAX_TRACK_FADE,
      onValueChange = {
        track.fadeOutSeconds = it
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Level",
      value = track.gain,
      valueLabel = formatPercent(track.gain),
      range = 0f..MAX_TRACK_GAIN,
      onValueChange = {
        track.gain = it
        state.onEditChanged()
      },
      onReset = {
        track.gain = 1f
        state.onEditChanged()
      },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Chip(label = "Add a file", selected = false, role = Role.Button, onClick = onAddClip)
      Chip(label = "Remove", selected = false, role = Role.Button, onClick = { state.removeTrack(track.id) })
    }
  }
}

private fun ClipEdit.line(): String =
  when {
    failure != null -> "$label could not be read: $failure"
    probing -> "$label, reading"
    else -> "$label, ${duration?.asClock().orEmpty()}"
  }

private fun secondsLabel(seconds: Float): String = "${(seconds * 10).roundToInt() / 10f}s"

private const val MIN_START_RANGE = 1f
private const val MAX_TRACK_FADE = 5f
private const val MAX_TRACK_GAIN = 2f
