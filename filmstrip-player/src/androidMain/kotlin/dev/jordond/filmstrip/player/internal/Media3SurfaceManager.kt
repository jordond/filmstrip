package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.view.View
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
 * The view is held twice. [attached] is the player's copy and is confined to the player's thread. [previewTarget] is
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
  var attached: PreviewTarget? = null
    private set

  // A swap freezes the surface on the engine's dispatcher, which is not the thread the player's copy is confined to.
  @Volatile
  var previewTarget: PreviewTarget? = null
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

  fun attach(target: PreviewTarget) {
    target.listen { onResized(target) }
    previewTarget = target

    // A view arriving after the graph takes the frame the graph is already rendering.
    scope.launch { fixBuffer(target, rendered()) }
    handler.post {
      attached = target
      player()?.let(target::applyTo)
      applications++
    }
  }

  /**
   * Lets go of [view], leaving a view attached since alone.
   */
  fun detach(view: View) {
    val preview = previewTarget
    if (preview?.view === view) {
      preview.stopListening()
      previewTarget = null
      scope.launch { bufferSize = null }
    }
    handler.post {
      val target = attached?.takeIf { it.view === view } ?: return@post
      attached = null
      player()?.let(target::clearFrom)
      scope.launch { onDetached() }
    }
  }

  /**
   * Lets go of the attached view as the player goes away. Player thread only.
   */
  fun release() {
    attached?.release(player())
    attached = null
  }

  /**
   * Fixes [target]'s buffer to [rendered], leaving it alone when nothing changed.
   *
   * See [previewBufferChange] for why the buffer follows the rendered frame rather than the view.
   */
  fun fixBuffer(
    target: PreviewTarget?,
    rendered: Size?,
  ) {
    if (target == null || rendered == null) return
    val next = previewBufferChange(bufferSize, rendered) ?: return
    bufferSize = next
    target.fixBuffer(next)
  }

  // Reacts to the surface changing shape under the player. See [surfaceResizeAction].
  private fun onResized(target: PreviewTarget) {
    handler.post {
      val standing = player() ?: return@post
      if (attached !== target) return@post

      when (surfaceResizeAction(graphUp())) {
        SurfaceResize.Reapply -> {
          // Clearing first is what makes it take: setting the view it already holds is a no-op,
          // so the output has to go away before it will be built again at the shape it now has.
          target.clearFrom(standing)
          target.applyTo(standing)
          applications++
        }
        SurfaceResize.Redraw -> {
          standing.experimentalRedrawLastFrame()
          redraws++
        }
      }
    }
  }
}
