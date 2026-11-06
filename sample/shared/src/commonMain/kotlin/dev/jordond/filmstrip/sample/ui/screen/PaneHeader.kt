package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.sample.ui.IconKey
import dev.jordond.filmstrip.sample.ui.SampleIcons

/**
 * The title row an overlay pane opens with.
 *
 * The close button is here rather than left to the scene, because a dialog is only dismissible by
 * clicking the window behind it, which is not obvious and is not reachable at all on a sheet.
 *
 * @param actions Anything that belongs beside the close button.
 */
@Composable
internal fun PaneHeader(
  title: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  closeEnabled: Boolean = true,
  actions: @Composable () -> Unit = {},
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      actions()
      IconKey(SampleIcons.Close, "Close $title", onClose, enabled = closeEnabled)
    }
  }
}
