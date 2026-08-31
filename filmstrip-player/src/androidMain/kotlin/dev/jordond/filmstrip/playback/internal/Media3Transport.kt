package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.SeekAccuracy

/**
 * The accuracy a seek will really run at here, given the one that was asked for.
 *
 * Always [SeekAccuracy.Exact], and that is media3's limit rather than a choice.
 * `CompositionPlayer` names no `SeekParameters` anywhere, its internal players are never given any,
 * and `SeekParameters` lives in a module the shared `Player` interface cannot depend on, so nothing
 * on this backend carries a tolerance. Scrubbing mode makes a burst of exact seeks cheaper without
 * making any of them approximate.
 *
 * So a relaxed request is clamped up, never down: a caller asking for [SeekAccuracy.Nearest] gets
 * the frame [SeekAccuracy.Exact] would have landed on, which costs more and is never further from
 * what was asked for.
 */
@Suppress("SameReturnValue")
internal fun clampedAccuracy(requested: SeekAccuracy): SeekAccuracy =
  when (requested) {
    SeekAccuracy.Exact -> SeekAccuracy.Exact
    SeekAccuracy.Nearest -> SeekAccuracy.Exact
  }

/**
 * What one `setComposition` costs the platform.
 */
internal enum class LoadCost {
  /**
   * No platform call at all, not even a status move.
   */
  Nothing,

  /**
   * The standing graph took the new parameters and redraws.
   */
  Parameters,

  /**
   * A fresh graph, which costs a decoder reinitialization.
   */
  Rebuild,
}

/**
 * What loading an edit costs, given how it changed and whether the standing graph took it.
 *
 * `CompositionPlayer.setComposition` reconfigures the whole pipeline whatever it is handed, so all
 * three of the behaviours `PlayerEngine.setComposition` promises are decided here rather than by
 * media3.
 *
 * @param change How the edit differs from the one already loaded.
 * @param hasGraph Whether a graph is standing to reuse.
 * @param swap Offers the new parameters to the standing graph, answering whether it took them. Only
 *   asked when the change is confined to parameters, so nothing is swapped into a graph that is
 *   about to be rebuilt anyway.
 */
internal fun loadCostFor(
  change: CompositionDiff,
  hasGraph: Boolean,
  swap: () -> Boolean,
): LoadCost =
  when {
    !hasGraph -> LoadCost.Rebuild
    change == CompositionDiff.Equal -> LoadCost.Nothing
    change == CompositionDiff.ParametersOnly && swap() -> LoadCost.Parameters
    else -> LoadCost.Rebuild
  }

/**
 * What a surface that changed shape needs from the player.
 */
internal enum class SurfaceResize {
  /**
   * Hand the output back, because media3 read the new size and dropped it.
   */
  Reapply,

  /**
   * Ask the graph to draw again into the buffer the surface now has.
   */
  Redraw,
}

/**
 * What to do about a surface that changed shape, given whether a graph is up to draw it.
 *
 * media3 watches the holder itself and applies the size it reads, except that it drops it while
 * `compositionPlayerInternal` is null, which lasts from the player being built until a composition
 * set on it is prepared. Handing the output back covers that window, and costs a teardown of the
 * graph's surface that blocks the player's thread until it completes, so it is not worth spending
 * where the size already landed.
 *
 * Once a graph is up the size is in force and the only thing missing is pixels: a resized buffer
 * comes up empty, and a graph with nothing to play draws into it when it is next asked.
 *
 * @param hasGraph Whether a graph has reached the point of being able to draw.
 */
internal fun surfaceResizeAction(hasGraph: Boolean): SurfaceResize =
  if (hasGraph) SurfaceResize.Redraw else SurfaceResize.Reapply

/**
 * The buffer a preview surface should be fixed to, or null to leave the one it has.
 *
 * A `SurfaceView` whose buffer tracks its layout reallocates every time the view changes shape, and
 * a reallocated buffer comes up empty. Doing that at the moment a swap is revealed puts the
 * reallocation in the one window where it is visible, so the buffer is fixed to the frame the graph
 * renders instead: it changes when the composition's rendered frame changes, which is under the
 * cover a swap already holds, and a view that moves afterwards is only scaled into.
 *
 * The rendered frame is the one the quality policy settled, so
 * [dev.jordond.filmstrip.player.PreviewInfo.renderScale] describes what is really being drawn
 * rather than whatever size the host's layout happened to give the view.
 *
 * @param current The buffer already fixed, or null where none has been.
 * @param rendered The frame the graph renders.
 */
internal fun previewBufferChange(
  current: Size?,
  rendered: Size,
): Size? =
  when {
    rendered.width <= 0 || rendered.height <= 0 -> null
    rendered == current -> null
    else -> rendered
  }
