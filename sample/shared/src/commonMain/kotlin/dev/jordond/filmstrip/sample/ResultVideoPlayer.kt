package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays an exported file back, so the written output can be eyeballed without leaving the app.
 */
@Composable
expect fun ResultVideoPlayer(
  path: String,
  modifier: Modifier = Modifier,
)
