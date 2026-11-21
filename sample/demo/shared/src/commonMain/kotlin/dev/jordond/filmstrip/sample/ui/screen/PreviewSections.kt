package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.filekit.compose.rememberShareLauncher
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.Key
import dev.jordond.filmstrip.sample.ui.KeyStyle
import dev.jordond.filmstrip.still.StillFormat

/**
 * The two things the editor does with the preview apart from playing it: capping how tall it renders, and saving the
 * frame under the playhead as a picture.
 */
@Composable
internal fun PreviewSections(state: SampleAppState) {
  ToolSection("Preview quality") {
    Text(
      "Caps the height the player renders at. The export is not touched.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChipGroup(
      options = listOf("Full" to null) + PREVIEW_CAPS.map { "$it" to it },
      selected = state.preview.previewCap,
      onSelect = { state.preview.previewCap = it },
    )
  }

  ToolSection("Save frame") {
    val frames = state.frames
    val sharer = rememberShareLauncher()
    ChipGroup(
      options = StillFormat.entries.map { it.name.uppercase() to it },
      selected = frames.format,
      onSelect = { frames.format = it },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Key(
        label = if (frames.saving) "SAVING" else "SAVE FRAME",
        onClick = state::saveFrame,
        enabled = state.canPlay && !frames.saving,
        style = KeyStyle.Secondary,
      )
      val saved = frames.saved
      if (sharer != null && saved != null && saved.output !is MediaSink.Temporary) {
        Key(label = "SHARE", onClick = { sharer.launch(saved.output) })
      }
    }
    frames.saved?.let { saved ->
      Text(
        "Saved ${saved.size.width} x ${saved.size.height} ${saved.format.name.uppercase()}",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    frames.failure?.let { ErrorLine(it) }
  }
}

private val PREVIEW_CAPS = listOf(720, 540, 360)
