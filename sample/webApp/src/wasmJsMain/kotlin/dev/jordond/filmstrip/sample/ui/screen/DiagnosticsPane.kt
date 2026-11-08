package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.diagnosticsReport
import dev.jordond.filmstrip.sample.ui.Key
import dev.jordond.filmstrip.sample.ui.KeyStyle
import dev.jordond.filmstrip.sample.ui.Panel
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.writeDiagnostics
import kotlinx.coroutines.launch

private const val BLURB =
  "Everything this session knows, in the order the bug template asks for it. File names are " +
    "reduced to a hash, so nothing here says what you picked or where it lives."

/**
 * The report, and the two ways of getting it off the device.
 *
 * It is rebuilt on every recomposition rather than cached, because the log behind it grows while
 * the pane is open and a stale report is worse than no report.
 */
@Composable
public fun DiagnosticsPane(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val report = state.diagnosticsReport()
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()

  var saved by remember { mutableStateOf(false) }
  var copied by remember { mutableStateOf(false) }

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(max = 620.dp)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(top = 8.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    PaneHeader(title = "Diagnostics", onClose = state::navigateBack)

    Text(BLURB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Key(
        label = "COPY REPORT",
        onClick = {
          scope.launch {
            clipboard.setClipEntry(ClipEntry.withPlainText(report.markdown))
            copied = true
          }
        },
        modifier = Modifier.weight(1f),
        icon = SampleIcons.Check,
        horizontalPadding = 8.dp,
      )

      Key(
        label = "SAVE FILES",
        onClick = {
          writeDiagnostics(report)
          saved = true
        },
        modifier = Modifier.weight(1f),
        style = KeyStyle.Secondary,
        horizontalPadding = 8.dp,
      )
    }

    if (copied) {
      Text(
        text = "Copied. Paste it into the issue.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }

    if (saved) {
      Text(
        text = "Downloaded the report and its json.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }

    Panel("REPORT", Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, bodyPadding = 12.dp) {
      Text(
        text = report.markdown,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
      )
    }
  }
}
