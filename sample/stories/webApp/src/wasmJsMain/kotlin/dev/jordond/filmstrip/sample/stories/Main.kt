package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.jordond.filmstrip.sample.ui.WithPreloadedFonts
import kotlinx.browser.document

/**
 * The browser story editor, rendered into the canvas `index.html` sets up.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    WithPreloadedFonts { WithStoryFonts { App() } }
  }
}
