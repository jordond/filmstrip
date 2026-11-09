package dev.jordond.filmstrip.sample.playground

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.allBackends
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.url.URL
import org.w3c.dom.url.URLSearchParams

/**
 * The docs playground. The page picks a sample with `?sample=`, and an unknown or missing name says so in place of a
 * sample.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  val filmstrip =
    Filmstrip {
      allBackends()
      filters()
    }
  val copy: (String) -> Unit = { window.navigator.clipboard.writeText(it) }
  val logo = ImageSource.ofUri(URL("logo.png", window.location.href).href)
  val sample = URLSearchParams(window.location.search.toJsString()).get("sample")
  ComposeViewport(document.body!!) {
    SampleTheme {
      when (sample) {
        "effects" -> EffectsPlayground(filmstrip, copy)
        "filters" -> FiltersPlayground(filmstrip, copy)
        "overlays" -> OverlaysPlayground(filmstrip, logo, copy)
        else -> Text("No sample called $sample", style = MaterialTheme.typography.bodyMedium, color = Tokens.red)
      }
    }
  }
}
