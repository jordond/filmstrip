package dev.jordond.filmstrip.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The desktop sample. The window opens wide enough for the editor's two pane layout, and shrinking
 * it below that stacks the editor the way a phone does.
 */
fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "filmstrip",
    state = rememberWindowState(size = DpSize(1180.dp, 820.dp)),
  ) {
    App(remember { createSampleAppState() })
  }
}
