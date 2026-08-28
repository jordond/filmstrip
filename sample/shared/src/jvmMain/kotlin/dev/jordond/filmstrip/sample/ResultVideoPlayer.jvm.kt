package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.awt.Desktop
import java.io.File

// Compose for desktop has no video surface of its own, so the file is handed to whatever the OS
// plays videos with instead of embedding a player here.
@Composable
actual fun ResultVideoPlayer(
  path: String,
  modifier: Modifier,
) {
  val openable = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

  PlaybackUnavailable(
    message = "Desktop playback happens outside the app.",
    modifier = modifier,
    actionLabel = if (openable) "Open in the system player" else null,
    action = if (openable) {
      { Desktop.getDesktop().open(File(path)) }
    } else {
      null
    },
  )
}
