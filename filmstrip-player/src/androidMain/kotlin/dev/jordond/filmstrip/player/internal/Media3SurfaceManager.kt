package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.view.View
import androidx.media3.common.Player
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
 * @param drawnOnto Whether a frame has reached the attached view. Read on the player's thread.
 * @param represent Seeks back to the slot the preview holds, and is told whether a surface going is why. Called on
 * the player's thread.
 */
@ExperimentalApi
internal class Media3SurfaceManager(
  private val scope: CoroutineScope,
  private val handler: Handler,
  private val player: () -> CompositionPlayer?,
  private val rendered: () -> Size?,
  private val graphUp: () -> Boolean,
  private val onDetached: () -> Unit,
  private val drawnOnto: () -> Boolean,
  private val represent: (surfaceLost: Boolean) -> Unit,
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

  /**
   * How many times a player that was not playing was seeked back to the slot it held, as its surface went or as it
   * was handed another.
   */
  @Volatile
  var represents: Int = 0
    private set

  fun attach(target: PreviewTarget) {
    target.listen { change -> onChanged(target, change) }
    previewTarget = target

    // A view arriving after the graph takes the frame the graph is already rendering.
    scope.launch { fixBuffer(target, rendered()) }
    handler.post {
      // A view taking over from one that was drawn onto is an output handed over with no warning.
      val replaces = attached != null && drawnOnto()
      attached = target
      val standing = player()
      standing?.let(target::applyTo)
      applications++
      if (standing != null && replaces && !standing.drawsOnItsOwn && graphUp()) seekBack(SurfaceChange.Handed)
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
      val standing = player()
      standing?.let(target::clearFrom)
      scope.launch { onDetached() }
      standing?.let { act(it, target, SurfaceChange.Lost) }
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

  // Reacts to the surface changing under the player. See [surfaceChangeAction].
  private fun onChanged(
    target: PreviewTarget,
    change: SurfaceChange,
  ) {
    handler.post {
      val standing = player() ?: return@post
      if (attached !== target) return@post
      act(standing, target, change)
    }
  }

  // Player thread only.
  private fun act(
    standing: CompositionPlayer,
    target: PreviewTarget,
    change: SurfaceChange,
  ) {
    when (surfaceChangeAction(graphUp(), change, standing.drawsOnItsOwn)) {
      SurfaceResize.Leave -> {}
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
      SurfaceResize.Represent -> {
        seekBack(change)
      }
    }
  }

  private fun seekBack(change: SurfaceChange) {
    represent(change == SurfaceChange.Lost)
    represents++
  }
}

// A player that ran to its end holds its last frame however it was left, so it counts as not drawing.
private val CompositionPlayer.drawsOnItsOwn: Boolean
  get() = playWhenReady && playbackState != Player.STATE_ENDED

/**
 * How many times the player has been pointed at a surface.
 *
 * One for the attachment, and one more each time the surface changed shape under it.
 */
internal val Media3PlayerEngine.surfaceApplications: Int get() = surfaces.applications

/**
 * How many times a resized surface has been asked to draw again.
 *
 * The cheap half of [surfaceApplications]: media3 has the new size already and only the pixels
 * are missing.
 */
internal val Media3PlayerEngine.surfaceRedraws: Int get() = surfaces.redraws

/**
 * How many times a preview that was not playing was seeked back to the slot it held, as its surface went or as it
 * was handed another.
 */
internal val Media3PlayerEngine.surfaceRepresents: Int get() = surfaces.represents
