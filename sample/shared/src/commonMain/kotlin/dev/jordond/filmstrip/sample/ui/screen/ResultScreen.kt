package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.sample.ResultVideoPlayer
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.rememberExportSharer
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.StatRow
import dev.jordond.filmstrip.sample.ui.asClock

/**
 * What came out: the file played back, what filmstrip said it wrote, and what a fresh probe of the
 * output says it actually is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ResultScreen(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val result = state.exported ?: return
  val onDismiss = state::navigateBack
  val path = when (val sink = result.output) {
    is MediaSink.Path -> sink.path
    is MediaSink.Uri -> sink.uri
    MediaSink.Temporary -> null
  }
  val sharer = rememberExportSharer()

  Scaffold(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = {
      TopAppBar(
        title = { Text("Export complete", style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
          IconButton(onClick = onDismiss) {
            Icon(SampleIcons.Close, contentDescription = "Back to the editor")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          titleContentColor = MaterialTheme.colorScheme.onSurface,
          navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
      )
    },
  ) { insets ->
    Column(Modifier.fillMaxSize().padding(insets)) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (path != null) {
          val aspect = result.info.video?.displaySize?.aspect?.takeIf { it > 0f } ?: (16f / 9f)
          Box(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentAlignment = Alignment.Center,
          ) {
            ResultVideoPlayer(
              path = path,
              modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            )
          }
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          StatRow("Duration", result.info.duration.asClock())
          result.info.video?.let { video ->
            StatRow("Frame", "${video.displaySize.width} x ${video.displaySize.height}")
            StatRow("Video", video.codec.name)
            video.bitrate?.let { StatRow("Bitrate", "${it.bitsPerSecond / 1_000_000} Mbps") }
          }
          result.info.audio?.let { audio ->
            StatRow("Audio", "${audio.codec.name} · ${audio.sampleRate} Hz · ${audio.channelCount} ch")
          }

          when (val probe = state.exportedInfo) {
            is ProbeResult.Success -> Pill("RE-PROBED · ${probe.info.duration.asClock()}", MaterialTheme.colorScheme.tertiary)
            is ProbeResult.Failure -> ErrorLine(probe.error)
            null -> Unit
          }
        }

        if (result.adjustments.isNotEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
              .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text("Adjusted on the way out", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            result.adjustments.forEach { adjustment ->
              Text(adjustment.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }

        if (path != null) {
          SelectionContainer {
            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.weight(1f).height(50.dp),
        ) {
          Text("Keep editing")
        }
        Button(
          onClick = { if (path != null && sharer != null) sharer(path) },
          enabled = path != null && sharer != null,
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
          modifier = Modifier.weight(1f).height(50.dp),
        ) {
          Text("Share")
        }
      }
    }
  }
}
