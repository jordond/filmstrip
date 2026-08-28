package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.diagnosticsReport
import dev.jordond.filmstrip.sample.rememberExportSharer
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
  val clipboard = LocalClipboardManager.current
  val sharer = rememberExportSharer()
  val scope = rememberCoroutineScope()

  var saving by remember { mutableStateOf(false) }
  var saved by remember { mutableStateOf<String?>(null) }
  var copied by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(max = 620.dp)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(top = 8.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    PaneHeader(title = "Diagnostics", onClose = state::navigateBack)

    Text(BLURB, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(
        onClick = {
          clipboard.setText(AnnotatedString(report.markdown))
          copied = true
        },
      ) {
        Icon(SampleIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        Text("   Copy report", style = MaterialTheme.typography.labelLarge)
      }

      OutlinedButton(
        onClick = {
          saving = true
          scope.launch {
            try {
              val path = writeDiagnostics(report)
              saved = path ?: "Downloaded."
              if (path != null) sharer?.invoke(path)
            } finally {
              saving = false
            }
          }
        },
        enabled = !saving,
      ) {
        if (saving) {
          CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        } else {
          Text("Save files", style = MaterialTheme.typography.labelLarge)
        }
      }
    }

    if (copied) {
      Text(
        text = "Copied. Paste it into the issue.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }

    saved?.let { location ->
      Text(
        text = "Wrote the report and its json to $location",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }

    Text(
      text = report.markdown,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
        .padding(12.dp)
        .horizontalScroll(rememberScrollState()),
    )
  }
}
