package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.browser.window

// Compose renders to a canvas, so a video element cannot be laid out inside it. The export is a
// blob url, which the browser plays perfectly well in a tab of its own.
@Composable
actual fun ResultVideoPlayer(
  path: String,
  modifier: Modifier,
) {
  PlaybackUnavailable(
    message = "Browser playback happens in a tab of its own.",
    modifier = modifier,
    actionLabel = "Open the export",
    action = { window.open(path, "_blank") },
  )
}
