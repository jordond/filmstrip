package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * The browser draws the composite itself, so there is no native view to attach.
 *
 * A `<video>` element would show the browser's own decode of the source rather than the
 * post-effect frame the encoder takes, so this draws the readback instead.
 */
@Composable
public actual fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
) {
  ReadbackSurface(player, modifier, contentScale)
}
