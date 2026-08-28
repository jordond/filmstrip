package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.player.PreviewSurfaceType
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * iOS renders into a `UIView` whose backing layer is an `AVPlayerLayer`.
 *
 * [surfaceType] is ignored here, because Apple offers one path.
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
