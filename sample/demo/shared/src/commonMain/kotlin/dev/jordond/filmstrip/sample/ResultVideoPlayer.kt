package dev.jordond.filmstrip.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Plays an exported file back, so the written output can be eyeballed without leaving the app.
 */
@Composable
public expect fun ResultVideoPlayer(
  path: String,
  modifier: Modifier = Modifier,
)

/**
 * What a target without an in-app player shows instead.
 *
 * @param action What the button does, or null for no button.
 */
@Composable
internal fun PlaybackUnavailable(
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  action: (() -> Unit)? = null,
) {
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (actionLabel != null && action != null) {
      OutlinedButton(onClick = action) {
        Text(actionLabel)
      }
    }
  }
}
