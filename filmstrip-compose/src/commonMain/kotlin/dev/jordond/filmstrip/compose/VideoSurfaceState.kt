package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.VideoPlayer

/**
 * What a preview needs to size itself and to know when it has something worth showing.
 *
 * Built by [rememberVideoSurfaceState], which keeps it fed from the player.
 */
@Stable
public class VideoSurfaceState internal constructor() {
  /**
   * The frame size the composition on screen outputs, zero until the engine has reported one.
   *
   * Feed it to [videoAspect] rather than sizing a preview from the source's dimensions.
   *
   * A size reported for a composition that is still loading waits here until its first frame
   * arrives, so the box and the picture in it move together. Read [VideoPlayer.previewInfo] for
   * what the engine has been asked to deliver rather than for what it is delivering.
   */
  public var outputSize: Size by mutableStateOf(EMPTY_SIZE)
    private set

  /**
   * Whether something should still be covering the surface.
   *
   * True until the loaded composition's first frame reaches the surface, and true again while the
   * next composition prepares. [outputSize] holds still for as long as this is true.
   */
  public var coverSurface: Boolean by mutableStateOf(true)
    private set

  // A size the engine has reported for a composition that has yet to draw anything.
  private var loading: Size? = null

  internal fun onOutputSize(size: Size) {
    // The first size has nothing on screen to protect, and one that arrives with nothing covering
    // the surface belongs to the composition already drawing.
    if (outputSize == EMPTY_SIZE || !coverSurface) {
      outputSize = size
      loading = null
    } else {
      loading = size
    }
  }

  internal fun onEvent(event: PlaybackEvent) {
    if (event is PlaybackEvent.FirstFrameRendered) present()
  }

  internal fun onStatus(status: PlaybackStatus) {
    when {
      status == PlaybackStatus.Preparing -> coverSurface = true
      // A load that failed draws nothing, so holding the surface covered for it would wait on a
      // frame that is never coming.
      status is PlaybackStatus.Error -> present()
      else -> Unit
    }
  }

  private fun present() {
    coverSurface = false
    loading?.let { outputSize = it }
    loading = null
  }

  private companion object {
    val EMPTY_SIZE = Size(0, 0)
  }
}

/**
 * Tracks [player]'s output size and whether its surface has pixels yet.
 *
 * @param player The player to follow.
 * @return State keyed to [player], the same instance across recompositions.
 */
@Composable
public fun rememberVideoSurfaceState(player: VideoPlayer): VideoSurfaceState {
  val state = remember(player) { VideoSurfaceState() }

  LaunchedEffect(player, state) {
    player.previewInfo.collect { info -> state.onOutputSize(info.outputSize) }
  }

  LaunchedEffect(player, state) {
    player.state.collect { snapshot -> state.onStatus(snapshot.status) }
  }

  PlaybackEventEffect(player) { event -> state.onEvent(event) }

  return state
}

/**
 * Constrains this layout to the aspect the composition outputs.
 *
 * Only [VideoContentScale.Fit] is sized from the video. The other two are asked to fill whatever
 * space they are given, so constraining them would defeat them. Inert until the engine reports an
 * output size.
 *
 * @param state The surface state holding the output size.
 * @param contentScale How the video fills the space it is given.
 */
public fun Modifier.videoAspect(
  state: VideoSurfaceState,
  contentScale: VideoContentScale,
): Modifier {
  if (contentScale != VideoContentScale.Fit) return this

  val aspect = state.outputSize.aspect
  return if (aspect <= 0f) this else aspectRatio(aspect)
}
