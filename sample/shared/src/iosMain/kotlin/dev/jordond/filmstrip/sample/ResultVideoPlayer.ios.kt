package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Same story as the picker: no iOS app harness yet, so the exported file is not played back here.
@Composable
actual fun ResultVideoPlayer(
  path: String,
  modifier: Modifier,
) {
  PlaybackUnavailable(
    message = "iOS playback of the exported file is not wired up yet.",
    modifier = modifier,
  )
}
