package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.player.PreviewSurfaceType
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * Renders [player]'s video output, and nothing else.
 *
 * No controls, no gestures, no shutter. Compose content drawn after this composable composites on
 * top of the video on both platforms.
 *
 * This composable never owns [player]. It only attaches and detaches the render target, so create
 * the player somewhere that outlives configuration changes and pass it in.
 *
 * Size the surface to the composition's output aspect rather than the source's. With effects
 * attached the surface letterboxes instead of stretching, so a container with the wrong ratio turns
 * mostly black. The output size is on the player's preview info.
 */
@Composable
public expect fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier = Modifier,
  contentScale: VideoContentScale = VideoContentScale.Fit,
  surfaceType: PreviewSurfaceType = PreviewSurfaceType.Surface,
)

/**
 * How the video rectangle fills the space [VideoSurface] is given.
 *
 * This is a view-level fit. Letterboxing here produces no pixels, while letterboxing in the
 * composition writes real bars into the file, so express an output frame with a crop rather than
 * with this.
 */
public enum class VideoContentScale {
  /**
   * Fit inside, preserving aspect. Bars appear in the view, not in the output.
   */
  Fit,

  /**
   * Fill and crop the overflow, preserving aspect.
   */
  Crop,

  /**
   * Stretch to fill, distorting.
   */
  Stretch,
}

/**
 * Remembers a player from [factory] and closes it when this leaves composition.
 *
 * `remember` survives recomposition but not a configuration change, so every rotation tears the
 * player down and rebuilds it. Hoist the player into something that outlives the screen for
 * anything longer-lived than a throwaway preview.
 *
 * @return The remembered player, the same instance across recompositions.
 */
@Composable
public fun rememberVideoPlayer(factory: () -> VideoPlayer): VideoPlayer =
  remember { ClosingPlayerHolder(factory()) }.player

@Stable
private class ClosingPlayerHolder(
  val player: VideoPlayer,
) : RememberObserver {
  override fun onAbandoned(): Unit = player.close()

  override fun onForgotten(): Unit = player.close()

  override fun onRemembered(): Unit = Unit
}
