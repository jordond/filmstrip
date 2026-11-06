package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.sample.resources.Res
import dev.jordond.filmstrip.sample.resources.rubik_mono_one_regular
import dev.jordond.filmstrip.sample.resources.space_mono_bold
import dev.jordond.filmstrip.sample.resources.space_mono_regular
import dev.jordond.filmstrip.sample.resources.vt323_regular
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.preloadFont

/**
 * Holds [content] back on the bare `ground` until every face has loaded.
 *
 * A browser fetches a font over the network, so without this the first frames draw in a fallback. The canvas is white
 * until something paints on it, so the wait paints the ground the page and the app both open on.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
public fun WithPreloadedFonts(content: @Composable () -> Unit) {
  val display by preloadFont(Res.font.rubik_mono_one_regular)
  val osd by preloadFont(Res.font.vt323_regular)
  val body by preloadFont(Res.font.space_mono_regular)
  val bodyBold by preloadFont(Res.font.space_mono_bold)
  if (listOf(display, osd, body, bodyBold).all { it != null }) {
    content()
  } else {
    Box(Modifier.fillMaxSize().background(Tokens.ground))
  }
}
