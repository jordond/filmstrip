package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The desktop story editor. The window opens wide enough for the stage with a tool rail and an inspector either side
 * of it.
 */
fun main() =
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "filmstrip stories",
      state = rememberWindowState(size = DpSize(1180.dp, 820.dp)),
    ) {
      App()
    }
  }
