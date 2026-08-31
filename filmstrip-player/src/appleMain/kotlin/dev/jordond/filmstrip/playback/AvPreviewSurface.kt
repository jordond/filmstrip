package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.playback.internal.engineFor
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.VideoPlayer
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer

/**
 * Hands the layer a preview surface draws into to the engine behind this player.
 *
 * The engine emits [PlaybackEvent.FirstFrameRendered] once [layer] starts showing the loaded
 * composition. A player some other backend built, or one with no backend at all, is left alone and
 * the returned handle does nothing.
 *
 * Main thread only. Cancel the handle before the layer goes away.
 *
 * @param layer The layer showing this player's output.
 * @return a handle that stops the engine watching [layer].
 */
@InternalFilmstripApi
public fun VideoPlayer.attachPreviewLayer(layer: AVPlayerLayer): Cancellable {
  val engine = (nativePlayer as? AVPlayer)?.let(::engineFor) ?: return Cancellable { }
  engine.attachSurfaceLayer(layer)
  return Cancellable { engine.detachSurfaceLayer(layer) }
}
