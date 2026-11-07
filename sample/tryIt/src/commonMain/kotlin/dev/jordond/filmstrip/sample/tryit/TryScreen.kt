package dev.jordond.filmstrip.sample.tryit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The picture the iframe draws, which is the edit playing once the clip is here. Each load gets its own player, which
 * opens on the steps on at that moment and takes every later edit from [engine].
 */
@Composable
internal fun TryScreen(
  engine: TryEngine,
  filmstrip: Filmstrip,
) {
  val state by engine.state.collectAsState()
  val source = state.source
  Box(Modifier.fillMaxSize().background(Tokens.ground)) {
    if (source != null) {
      key(state.attempt) {
        val opening = remember(source) { beachEdit(source, state.steps) }
        val player = rememberFilmstripPlayer(filmstrip, opening)
        LaunchedEffect(player) { engine.watch(player) }
        VideoSurface(player, Modifier.fillMaxSize())
      }
    }
  }
}
