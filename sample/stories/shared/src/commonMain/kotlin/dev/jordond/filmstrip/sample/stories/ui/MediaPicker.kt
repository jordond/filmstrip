package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.filekit.compose.rememberMediaPickerLauncher
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.loadPreset
import dev.jordond.filmstrip.sample.presetsAvailable
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The first screen: pick the clip the story is built on, from the device or from the sample clips.
 *
 * @param onPicked Called with the clip once it is ready to play, and whether it came from the file picker.
 */
@Composable
internal fun MediaPicker(onPicked: (source: MediaSource, picked: Boolean) -> Unit) {
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf<SamplePreset?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  val picker =
    rememberMediaPickerLauncher(
      onError = { failure = it.message ?: "The clip could not be opened" },
      onResult = { picked -> picked?.let { onPicked(it.source, true) } },
    )

  LazyColumn(
    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    contentPadding = PaddingValues(20.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item {
      Column(Modifier.widthIn(max = ROW_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("New story", style = MaterialTheme.typography.displaySmall, color = Tokens.text)
        Text(
          "Pick a clip. It fills an upright frame, and text, stickers and a drawing go over it.",
          style = MaterialTheme.typography.bodyMedium,
          color = Tokens.muted,
        )
        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.red) }
      }
    }
    item { PickerRow("Open a clip", "From this device", highlighted = true) { picker.launch() } }
    if (presetsAvailable) {
      items(samplePresets, key = { it.file }) { preset ->
        PickerRow(
          title = preset.name,
          detail = if (loading == preset) "Loading" else preset.detail,
          enabled = loading == null,
        ) {
          loading = preset
          failure = null
          scope.launch {
            try {
              onPicked(loadPreset(preset), false)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Throwable) {
              failure = error.message ?: "The clip did not load"
            } finally {
              loading = null
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PickerRow(
  title: String,
  detail: String,
  highlighted: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .widthIn(max = ROW_WIDTH)
        .fillMaxWidth()
        .background(Tokens.panel)
        .border(1.dp, if (highlighted) Tokens.amber else Tokens.line)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = if (highlighted) Tokens.amber else Tokens.text)
    Text(detail, style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
  }
}

private val ROW_WIDTH = 520.dp
