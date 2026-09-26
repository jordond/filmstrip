package dev.jordond.filmstrip.playback.internal

import android.os.Handler
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Keeps the player pointed at the view a preview draws into, and that view's buffer fixed to the frame the graph
 * renders.
 *
 * The view is held twice. [attached] is the player's copy and is confined to the player's thread. [previewView] is
 * the same view where the engine's dispatcher can reach it.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param handler Posts onto the player's thread.
 * @param player The player standing now, read on the player's thread.
 * @param rendered The size of the frame the loaded graph renders, or null before one is loaded.
 * @param graphUp Whether media3 has a graph to hand a size read off the holder to. Read on the player's thread.
 * @param onDetached Called on the engine's dispatcher once the player lets go of a view, so the next one earns its
 * own first frame.
 */
@ExperimentalApi
internal class Media3SurfaceManager(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val player: () -> CompositionPlayer?,
  private val rendered: () -> Size?,
  private val graphUp: () -> Boolean,
  private val onDetached: () -> Unit,
) {
  /**
   * The view the player draws into. Player thread only. The surface posts its attachment across rather than writing
   * it here.
   */
  var attached: SurfaceView? = null
    private set

  // A swap freezes the surface on the engine's dispatcher, which is not the thread the player's copy is confined to.
  @Volatile
  var previewView: SurfaceView? = null
    private set

  // The buffer the attached view's holder has been fixed to, so a plan that renders the same frame
  // as the last one leaves it alone.
  private var bufferSize: Size? = null

  /**
   * How many times the player has been pointed at a surface.
   *
   * One for the attachment, and one more each time the surface changed shape under it.
   */
  @Volatile
  var applications: Int = 0
    private set

  /**
   * How many times a resized surface has been asked to draw again.
   *
   * The cheap half of [applications]: media3 has the new size already and only the pixels
   * are missing.
   */
  @Volatile
  var redraws: Int = 0
    private set

  fun attach(view: SurfaceView) {
    view.holder.addCallback(surfaceResizes)
    previewView = view

    // A view arriving after the graph takes the frame the graph is already rendering.
    scope.launch { fixBuffer(view, rendered()) }
    handler.post {
      attached = view
      player()?.setVideoSurfaceView(view)
      applications++
    }
  }

  fun detach(view: SurfaceView) {
    view.holder.removeCallback(surfaceResizes)
    if (previewView === view) {
      previewView = null
      scope.launch { bufferSize = null }
    }
    handler.post {
      if (attached !== view) return@post
      attached = null
      player()?.clearVideoSurfaceView(view)
      scope.launch { onDetached() }
    }
  }

  /**
   * Forgets the attached view as the player goes away. Player thread only.
   */
  fun release() {
    attached = null
  }

  /**
   * Fixes [view]'s buffer to [rendered], leaving it alone when nothing changed.
   *
   * See [previewBufferChange] for why the buffer follows the rendered frame rather than the view.
   */
  fun fixBuffer(
    view: SurfaceView?,
    rendered: Size?,
  ) {
    val holder = view?.holder ?: return
    val target = rendered ?: return
    val next = previewBufferChange(bufferSize, target) ?: return
    bufferSize = next
    holder.setFixedSize(next.width, next.height)
  }

  /**
   * Reacts to the surface changing shape under the player. See [surfaceResizeAction].
   */
  private val surfaceResizes =
    object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder): Unit = Unit

      override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
      ) {
        handler.post {
          val standing = player() ?: return@post
          val view = attached ?: return@post
          if (view.holder !== holder) return@post

          when (surfaceResizeAction(graphUp())) {
            SurfaceResize.Reapply -> {
              // Clearing first is what makes it take: setting the view it already holds is a no-op,
              // so the output has to go away before it will be built again at the shape it now has.
              standing.clearVideoSurfaceView(view)
              standing.setVideoSurfaceView(view)
              applications++
            }
            SurfaceResize.Redraw -> {
              standing.experimentalRedrawLastFrame()
              redraws++
            }
          }
        }
      }

      override fun surfaceDestroyed(holder: SurfaceHolder): Unit = Unit
    }
}
