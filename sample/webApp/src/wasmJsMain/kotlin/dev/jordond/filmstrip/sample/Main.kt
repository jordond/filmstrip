package dev.jordond.filmstrip.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * The browser sample, rendered into the canvas `index.html` sets up.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val state = createSampleAppState()
  ComposeViewport(document.body!!) {
    App(state)
  }
}
