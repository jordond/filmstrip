package dev.jordond.filmstrip.sample.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.sample.stories.resources.Res
import dev.jordond.filmstrip.sample.stories.resources.dm_serif_display_regular
import dev.jordond.filmstrip.sample.stories.resources.pacifico_regular
import dev.jordond.filmstrip.sample.ui.Tokens
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

/**
 * Holds [content] back on the bare ground until the caption faces the app bundles have loaded.
 *
 * A browser fetches a font over the network, so without this a caption in one of them is first drawn, and could be
 * baked, in a fallback face.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
public fun WithStoryFonts(content: @Composable () -> Unit) {
  val script by preloadFont(Res.font.pacifico_regular)
  val serif by preloadFont(Res.font.dm_serif_display_regular)
  if (script != null && serif != null) {
    content()
  } else {
    Box(Modifier.fillMaxSize().background(Tokens.ground))
  }
}
