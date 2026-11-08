package dev.jordond.filmstrip.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.jordond.filmstrip.sample.ui.InsideTube
import dev.jordond.filmstrip.sample.ui.WithPreloadedFonts
import dev.jordond.filmstrip.sample.ui.isTubeEmbed
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * The browser sample, rendered into the canvas `index.html` sets up.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val state = createSampleAppState()
  val embedded = isTubeEmbed(window.location.search)
  ComposeViewport(document.body!!) {
    WithPreloadedFonts {
      if (embedded) InsideTube { App(state) } else App(state)
    }
  }
}
