package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.ClipEdit
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.TimelineView
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.IconKey
import dev.jordond.filmstrip.sample.ui.Led
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.TextKey
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.focusRing

/**
 * The clips of the edit in the order they play, over the timeline.
 *
 * A chip selects its clip and brings the playhead onto it. The keys beside the chips move the selected clip one
 * place either way, drop it, and add another: a file, a picture, a colour or one of the built-in clips. The two
 * words at the start switch the strip between the whole edit and the selected clip. A clip that could not be
 * added says why under the row.
 *
 * @param onAddClip Opens the platform picker for a clip to append.
 * @param onAddImage Opens the platform picker for a picture to append.
 */
@Composable
internal fun SequenceBar(
  state: SampleAppState,
  onAddClip: () -> Unit,
  onAddImage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      SequenceKeys(state, onAddClip, onAddImage)
    }
    state.pickFailure?.let { failure ->
      Text(
        text = failure,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun RowScope.SequenceKeys(
  state: SampleAppState,
  onAddClip: () -> Unit,
  onAddImage: () -> Unit,
) {
  val sequence = state.sequence
  val clips = sequence.clips
  val selected = sequence.selectedClip
  val index = clips.indexOf(selected)

  ViewKey("EDIT", TimelineView.Sequence, state)
  ViewKey("CLIP", TimelineView.Clip, state)

  Row(
    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    clips.forEachIndexed { place, clip ->
      ClipChip(
        clip = clip,
        number = place + 1,
        selected = clip.id == sequence.selected,
        onClick = { state.focusClip(clip.id) },
      )
    }
  }

  IconKey(
    icon = SampleIcons.ChevronRight,
    contentDescription = "Move the clip earlier",
    onClick = { selected?.let { state.moveClip(it.id, index - 1) } },
    modifier = Modifier.rotate(HALF_TURN),
    enabled = index > 0,
  )
  IconKey(
    icon = SampleIcons.ChevronRight,
    contentDescription = "Move the clip later",
    onClick = { selected?.let { state.moveClip(it.id, index + 1) } },
    enabled = index in 0 until clips.lastIndex,
  )
  IconKey(
    icon = SampleIcons.Close,
    contentDescription = "Remove the clip",
    onClick = { selected?.let { state.removeClip(it.id) } },
    enabled = selected != null && clips.size > 1,
  )
  AddKey(state, onAddClip, onAddImage)
}

@Composable
private fun ViewKey(
  label: String,
  view: TimelineView,
  state: SampleAppState,
) {
  val colors = MaterialTheme.colorScheme
  val active = state.timelineView == view
  TextKey(
    label = label,
    onClick = { state.timelineView = view },
    modifier = Modifier.semantics { selected = active },
    color = if (active) colors.primary else colors.onSurfaceVariant,
  )
}

@Composable
private fun ClipChip(
  clip: ClipEdit,
  number: Int,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val interaction = remember { MutableInteractionSource() }
  val failed = clip.failure != null
  val ink =
    when {
      failed -> colors.error
      selected -> colors.primary
      else -> colors.onSurfaceVariant
    }

  Row(
    modifier =
      Modifier
        .focusRing(interaction)
        .defaultMinSize(minHeight = CHIP_HEIGHT)
        .background(if (selected) colors.surfaceContainer else Color.Transparent)
        .border(BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant))
        .semantics { this.selected = selected }
        .clickable(role = Role.Tab, interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text("$number", style = MaterialTheme.typography.labelMedium, color = ink)
    Text(
      text = clip.label,
      modifier = Modifier.widthIn(max = CHIP_LABEL_MAX),
      style = MaterialTheme.typography.labelMedium,
      color = if (selected) colors.onSurface else colors.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    when {
      failed -> {
        Icon(SampleIcons.Warning, contentDescription = "Unreadable", tint = ink, modifier = Modifier.size(14.dp))
      }
      clip.probing -> {
        Led(LedState.Busy, blinking = true)
      }
      else -> {
        clip.duration?.let {
          Text(
            it.asClock(),
            style = MaterialTheme.typography.labelSmall,
            color = ink,
            maxLines = 1,
          )
        }
      }
    }
  }
}

/**
 * The key that appends a clip: a file, a picture, a colour or one of the built-in clips. It is off while a
 * built-in clip is downloading.
 */
@Composable
private fun AddKey(
  state: SampleAppState,
  onAddClip: () -> Unit,
  onAddImage: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconKey(
      icon = SampleIcons.Plus,
      contentDescription = "Add a clip",
      onClick = { open = true },
      enabled = state.loadingPreset == null,
      tint = MaterialTheme.colorScheme.primary,
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(
        text = { Text("From a file") },
        onClick = {
          open = false
          onAddClip()
        },
      )
      DropdownMenuItem(
        text = { Text("A picture") },
        onClick = {
          open = false
          onAddImage()
        },
      )
      DropdownMenuItem(
        text = { Text("A colour") },
        onClick = {
          open = false
          state.addSolid()
        },
      )
      samplePresets.forEach { preset ->
        DropdownMenuItem(
          text = { Text(preset.name) },
          onClick = {
            open = false
            state.addPreset(preset)
          },
        )
      }
    }
  }
}

private val CHIP_HEIGHT = 32.dp
private val CHIP_LABEL_MAX = 140.dp
private const val HALF_TURN = 180f
