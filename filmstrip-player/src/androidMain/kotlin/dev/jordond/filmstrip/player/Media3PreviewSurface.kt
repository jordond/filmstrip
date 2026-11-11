package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.player.internal.engineFor

/**
 * Hands the view a preview surface draws into to the engine behind this player.
 *
 * The engine emits [PlaybackEvent.FirstFrameRendered] once [view] starts showing the loaded
 * composition. A player some other backend built, or one with no backend at all, is left alone and
 * the returned handle does nothing.
 *
 * media3 confines every call on its player to the looper the engine built it on, which is not the
 * one a composable runs on, so the attachment is posted rather than applied here. Callable from any
 * thread. Cancel the handle before the view goes away.
 *
 * @param view The view showing this player's output.
 * @return a handle that stops the engine drawing into [view].
 */
@OptIn(ExperimentalApi::class)
@InternalFilmstripApi
public fun VideoPlayer.attachPreviewSurface(view: SurfaceView): Cancellable {
  val engine = (nativePlayer as? CompositionPlayer)?.let(::engineFor) ?: return Cancellable { }
  engine.attachSurfaceView(view)
  return Cancellable { engine.detachSurfaceView(view) }
}

/**
 * Hands the texture a preview surface draws into to the engine behind this player.
 *
 * The same contract as the [SurfaceView] overload, for a host that needs the video inside the view hierarchy, where
 * it can be faded, clipped and drawn over like any other view. The engine owns a surface over the view's texture
 * from the moment the texture exists until it is destroyed or the handle is cancelled, and takes the view's
 * `surfaceTextureListener` for as long. A texture shows SDR only, and costs a copy of every frame.
 *
 * Callable from any thread. Cancel the handle before the view goes away.
 *
 * @param view The view showing this player's output.
 * @return a handle that stops the engine drawing into [view].
 */
@OptIn(ExperimentalApi::class)
@InternalFilmstripApi
public fun VideoPlayer.attachPreviewSurface(view: TextureView): Cancellable {
  val engine = (nativePlayer as? CompositionPlayer)?.let(::engineFor) ?: return Cancellable { }
  engine.attachTextureView(view)
  return Cancellable { engine.detachTextureView(view) }
}

/**
 * Follows the frame this player holds over its preview surface while it swaps compositions.
 *
 * A swap that changes the output geometry resizes the surface as soon as the plan is applied and
 * leaves media3 rebuilding behind it, so [onStill] hands out the frame the surface last drew and
 * then null once the new graph has drawn its own. Draw the still over the surface, letterboxed from
 * its own dimensions, and the change reads as one move instead of two.
 *
 * A still is dropped on its own after a second, so a swap that never renders cannot leave the
 * preview frozen. A player some other backend built, or one with no backend at all, reports null
 * and nothing else.
 *
 * Call from the main thread, which is where the engine confines everything a listener sees.
 *
 * @param onStill Receives the frame to hold, or null to show the live surface. Called at once with
 * whatever is standing.
 * @return a handle that stops the reports.
 */
@OptIn(ExperimentalApi::class)
@InternalFilmstripApi
public fun VideoPlayer.observePreviewStill(onStill: (Bitmap?) -> Unit): Cancellable {
  val engine = (nativePlayer as? CompositionPlayer)?.let(::engineFor)
  if (engine == null) {
    onStill(null)
    return Cancellable { }
  }
  return engine.observeStill(onStill)
}
