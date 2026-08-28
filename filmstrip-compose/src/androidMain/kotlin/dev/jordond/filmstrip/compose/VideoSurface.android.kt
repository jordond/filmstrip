package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.player.PreviewSurfaceType
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Android renders into a `SurfaceView` by default, or a `TextureView` when [surfaceType] asks for
 * one.
 *
 * A `SurfaceView` carries HDR and protected content but ignores the UI layer's clipping, alpha and
 * transforms. Ask for a `TextureView` when the video rectangle itself is animated.
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
