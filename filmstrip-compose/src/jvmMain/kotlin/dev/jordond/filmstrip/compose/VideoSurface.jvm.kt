package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.player.PreviewSurfaceType
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Desktop has no preview backend, so this draws nothing.
 *
 * The bindings target the JVM so a Compose Multiplatform desktop app resolves them and shares one
 * source set with its mobile targets. There is no desktop [VideoPlayer] behind them yet: `preview`
 * returns a player reporting the missing backend, and this composable is a placeholder of the size
 * it is given rather than a video surface. [contentScale] and [surfaceType] are both ignored.
 */
@Composable
public actual fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
  surfaceType: PreviewSurfaceType,
) {
  Box(modifier)
}
