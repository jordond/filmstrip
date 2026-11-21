package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.EnvelopeAnchor
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.sample.ClipAudioMode
import dev.jordond.filmstrip.sample.ClipContent
import dev.jordond.filmstrip.sample.ClipEdit
import dev.jordond.filmstrip.sample.DUCK_ENVELOPE
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.FADE_ENVELOPE
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.addEnvelopePoint
import dev.jordond.filmstrip.sample.asSeconds
import dev.jordond.filmstrip.sample.audioMode
import dev.jordond.filmstrip.sample.envelope
import dev.jordond.filmstrip.sample.removeEnvelopePoint
import dev.jordond.filmstrip.sample.setEnvelopePoint
import dev.jordond.filmstrip.sample.sliderOfSpeed
import dev.jordond.filmstrip.sample.speedOfSlider
import dev.jordond.filmstrip.sample.ui.Chip
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.IconKey
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.SampleRangeSlider
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.SwatchRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.TextKey
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.excludeBackGesture
import dev.jordond.filmstrip.sample.ui.formatPercent
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Every knob that belongs to the selected clip alone: its trim and snap, its speed, its fades, its pan, its level
 * and which of the file's audio tracks it plays. A picture or a colour has how long it is held instead of a trim.
 */
@Composable
internal fun ClipPanel(state: SampleAppState) {
  val clip = state.sequence.selectedClip ?: return
  if (clip.content !is ClipContent.Video) {
    HoldSection(state, clip)
    FadeSection(state, clip)
    if (clip.content is ClipContent.Image) KenBurnsSection(state, clip)
    return
  }

  TrimSection(state, clip)
  SpeedSection(state, clip)
  FadeSection(state, clip)
  KenBurnsSection(state, clip)
  LevelSection(state, clip)

  val tracks = clip.info?.audioTracks.orEmpty()
  if (tracks.size > 1) TrackSection(state, clip, tracks)
}

@Composable
private fun TrimSection(
  state: SampleAppState,
  clip: ClipEdit,
) {
  val duration = clip.sourceDuration?.let { it.inWholeMilliseconds / 1000f } ?: 0f

  ToolSection("Trim") {
    SwitchRow(
      label = "Trim",
      supporting = "Keeps one range of the source. The handles are on the clip strip",
      checked = clip.trimEnabled,
      onCheckedChange = {
        clip.trimEnabled = it
        if (it && clip.trimEndSeconds <= clip.trimStartSeconds) clip.trimEndSeconds = duration
        state.onEditChanged()
      },
    )

    if (clip.trimEnabled && duration > 0f) {
      SampleRangeSlider(
        value = clip.trimStartSeconds..clip.trimEndSeconds,
        onValueChange = { range ->
          clip.trimStartSeconds = range.start
          clip.trimEndSeconds = range.endInclusive
          state.onEditChanged()
        },
        valueRange = 0f..duration,
        modifier = Modifier.excludeBackGesture(),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ClockLabel(clip.trimStartSeconds.asSeconds().asClock())
        Text(
          "${(clip.duration ?: Duration.ZERO).asClock()} kept",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
        ClockLabel(clip.trimEndSeconds.asSeconds().asClock())
      }

      Note("Snap the cut back to a key frame, which lets an untouched clip copy instead of encode")
      ChipGroup(
        options = listOf("Never" to 0f, "0.5s" to 0.5f, "2s" to 2f, "5s" to 5f),
        selected = clip.snapWithinSeconds,
        onSelect = {
          clip.snapWithinSeconds = it
          state.onEditChanged()
        },
      )
    }
  }
}

@Composable
private fun HoldSection(
  state: SampleAppState,
  clip: ClipEdit,
) {
  ToolSection("Hold") {
    SliderRow(
      label = "Length",
      value = clip.holdSeconds,
      valueLabel = secondsLabel(clip.holdSeconds),
      range = MIN_HOLD_SECONDS..MAX_HOLD_SECONDS,
      onValueChange = {
        clip.holdSeconds = it
        state.onEditChanged()
      },
      onReset = {
        clip.holdSeconds = ClipEdit.DEFAULT_HOLD_SECONDS
        state.onEditChanged()
      },
    )
  }

  if (clip.content == ClipContent.Solid) {
    ToolSection("Color") {
      SwatchRow(
        colors = Plates,
        selected = clip.color,
        onSelect = {
          clip.color = it ?: EditState.BLACK
          state.onEditChanged()
        },
      )
    }
  }
}

@Composable
private fun SpeedSection(
  state: SampleAppState,
  clip: ClipEdit,
) {
  ToolSection("Speed") {
    ChipGroup(
      options = SPEED_CHIPS.map { speedLabel(it) to it },
      selected = clip.speed,
      onSelect = {
        clip.speed = it
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Speed",
      value = sliderOfSpeed(clip.speed),
      valueLabel = speedLabel(clip.speed),
      range = 0f..1f,
      onValueChange = {
        clip.speed = speedOfSlider(it)
        state.onEditChanged()
      },
      onReset = {
        clip.speed = 1.0
        state.onEditChanged()
      },
    )
    Note("The audio keeps its pitch. The clip runs ${(clip.duration ?: Duration.ZERO).asClock()} at this speed")
  }
}

@Composable
private fun FadeSection(
  state: SampleAppState,
  clip: ClipEdit,
) {
  ToolSection("Audio fades") {
    SliderRow(
      label = "Fade in",
      value = clip.fadeInSeconds,
      valueLabel = secondsLabel(clip.fadeInSeconds),
      range = 0f..MAX_FADE_SECONDS,
      onValueChange = {
        clip.fadeInSeconds = it
        state.onEditChanged()
      },
      onReset = {
        clip.fadeInSeconds = 0f
        state.onEditChanged()
      },
    )
    SliderRow(
      label = "Fade out",
      value = clip.fadeOutSeconds,
      valueLabel = secondsLabel(clip.fadeOutSeconds),
      range = 0f..MAX_FADE_SECONDS,
      onValueChange = {
        clip.fadeOutSeconds = it
        state.onEditChanged()
      },
      onReset = {
        clip.fadeOutSeconds = 0f
        state.onEditChanged()
      },
    )
  }
}

@Composable
private fun LevelSection(
  state: SampleAppState,
  clip: ClipEdit,
) {
  ToolSection("Clip level") {
    Note("Levels multiply down the scopes, so a mute here silences this clip alone")
    ChipGroup(
      options =
        listOf(
          "Inherit" to ClipAudioMode.Inherit,
          "Mute" to ClipAudioMode.Mute,
          "Volume" to ClipAudioMode.Volume,
          "Envelope" to ClipAudioMode.Envelope,
        ),
      selected = clip.audioMode,
      onSelect = {
        clip.audioMode = it
        state.onEditChanged()
      },
    )

    when (val level = clip.audio) {
      is AudioLevel.Volume -> {
        SliderRow(
          label = "Gain",
          value = level.gain,
          valueLabel = formatPercent(level.gain),
          range = 0f..MAX_GAIN,
          onValueChange = {
            clip.audio = AudioLevel.Volume(it)
            state.onEditChanged()
          },
          onReset = {
            clip.audio = AudioLevel.Volume(1f)
            state.onEditChanged()
          },
        )
      }
      is AudioLevel.Envelope -> {
        EnvelopeEditor(state, clip)
      }
      AudioLevel.Inherit, AudioLevel.Mute -> {}
    }
  }
}

/**
 * The envelope's points, one block each, with the two ready-made shapes above them.
 *
 * A point reads its time from the start of the clip or back from its end, which is what lets a shape hold onto
 * the end of a clip whose length changes.
 */
@Composable
private fun EnvelopeEditor(
  state: SampleAppState,
  clip: ClipEdit,
) {
  val reach = ((clip.duration ?: Duration.ZERO).inWholeMilliseconds / 1000f).coerceAtLeast(1f)

  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    Chip(
      label = "Duck",
      selected = clip.envelope == DUCK_ENVELOPE,
      onClick = {
        clip.audio = AudioLevel.Envelope(DUCK_ENVELOPE)
        state.onEditChanged()
      },
    )
    Chip(
      label = "Fade",
      selected = clip.envelope == FADE_ENVELOPE,
      onClick = {
        clip.audio = AudioLevel.Envelope(FADE_ENVELOPE)
        state.onEditChanged()
      },
    )
    TextKey(
      label = "ADD POINT",
      onClick = {
        clip.addEnvelopePoint()
        state.onEditChanged()
      },
    )
  }

  if (clip.envelope.isEmpty()) Note("No points yet, which leaves the level alone")

  clip.envelope.forEachIndexed { index, point ->
    val change = { next: EnvelopePoint ->
      clip.setEnvelopePoint(index, next)
      state.onEditChanged()
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "POINT ${index + 1}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Chip(
        label = "From start",
        selected = point.from == EnvelopeAnchor.Start,
        onClick = { change(EnvelopePoint(point.at, point.gain, EnvelopeAnchor.Start)) },
      )
      Chip(
        label = "From end",
        selected = point.from == EnvelopeAnchor.End,
        onClick = { change(EnvelopePoint(point.at, point.gain, EnvelopeAnchor.End)) },
      )
      IconKey(
        icon = SampleIcons.Close,
        contentDescription = "Remove point ${index + 1}",
        onClick = {
          clip.removeEnvelopePoint(index)
          state.onEditChanged()
        },
      )
    }
    val seconds = point.at.inWholeMilliseconds / 1000f
    SliderRow(
      label = "At",
      value = seconds.coerceAtMost(reach),
      valueLabel = secondsLabel(seconds),
      range = 0f..reach,
      onValueChange = { change(EnvelopePoint(it.asSeconds(), point.gain, point.from)) },
      onReset = { change(EnvelopePoint(Duration.ZERO, point.gain, point.from)) },
    )
    SliderRow(
      label = "Gain",
      value = point.gain,
      valueLabel = formatPercent(point.gain),
      range = 0f..MAX_GAIN,
      onValueChange = { change(EnvelopePoint(point.at, it, point.from)) },
      onReset = { change(EnvelopePoint(point.at, 1f, point.from)) },
    )
  }
}

@Composable
private fun TrackSection(
  state: SampleAppState,
  clip: ClipEdit,
  tracks: List<AudioTrackInfo>,
) {
  ToolSection("Audio track") {
    Note("The file carries ${tracks.size} audio tracks. A clip plays one of them")
    ChipGroup(
      options =
        listOf<Pair<String, AudioTrackId?>>("Default" to null) +
          tracks.mapIndexed { index, track -> track.chipLabel(index) to track.id },
      selected = clip.audioTrack,
      onSelect = {
        clip.audioTrack = it
        state.onEditChanged()
      },
    )
  }
}

private fun AudioTrackInfo.chipLabel(index: Int): String =
  listOfNotNull("${index + 1}", language, "${channelCount}ch").joinToString(" ")

@Composable
private fun ClockLabel(text: String) {
  Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Note(text: String) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun speedLabel(speed: Double): String = "${(speed * 100).roundToInt() / 100.0}x"

private fun secondsLabel(seconds: Float): String = "${(seconds * 10).roundToInt() / 10f}s"

private val SPEED_CHIPS = listOf(0.25, 0.5, 1.0, 2.0, 4.0)
private const val MAX_FADE_SECONDS = 5f
private const val MIN_HOLD_SECONDS = 1f
private const val MAX_HOLD_SECONDS = 10f
private const val MAX_GAIN = 2f
