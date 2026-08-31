package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Desktop draws the readback, the same way the browser does.
 *
 * The frames come off the ffmpeg pump behind [VideoPlayer.readback], which is the only display path
 * this platform has: there is no desktop player object to attach a surface to.
 */
@Composable
public actual fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
) {
  ReadbackSurface(player, modifier, contentScale)
}
