package dev.jordond.filmstrip.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement

/**
 * Plays an exported file back, so the written output can be eyeballed without leaving the page.
 *
 * The export is an object url, which a video element plays over the canvas and a button opens in a tab of its own.
 *
 * @param path The object url of the export.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun ResultVideoPlayer(
  path: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HtmlElementView(
      factory = {
        (document.createElement("video") as HTMLVideoElement).apply {
          controls = true
          style.width = "100%"
          style.height = "100%"
        }
      },
      modifier = Modifier.weight(1f).fillMaxWidth(),
      update = { video -> if (video.src != path) video.src = path },
    )
    OutlinedButton(onClick = { window.open(path, "_blank") }) {
      Text("Open the export")
    }
  }
}
