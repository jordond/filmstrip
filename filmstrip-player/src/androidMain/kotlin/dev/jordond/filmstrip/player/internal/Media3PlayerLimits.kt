package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.SeekAccuracy
import kotlin.time.Duration

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
 *
 * A shuttle is a run of these seeks, so every frame it shows is decoded from the sync frame before it. That is what
 * holds a shuttle through 4K footage to a few frames a second, most of all in reverse.
 */
@Suppress("SameReturnValue")
internal fun clampedAccuracy(requested: SeekAccuracy): SeekAccuracy =
  when (requested) {
    SeekAccuracy.Exact -> SeekAccuracy.Exact
    SeekAccuracy.Nearest -> SeekAccuracy.Exact
  }

/**
 * This time in the whole milliseconds `Player.seekTo` takes, floored.
 *
 * Whole milliseconds are media3's limit, and a slot at a rate like 30 fps falls between two of them.
 * `CompositionPlayer` shows the first frame at or after the position it is handed, so the floor still lands on the
 * frame at this time, while rounding up would show the frame after it.
 */
internal fun Duration.toMediaMs(): Long = inWholeMilliseconds

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
 * What happened to the surface a preview draws into.
 */
internal enum class SurfaceChange {
  /**
   * The buffer changed shape and the player was told nothing.
   */
  Shape,

  /**
   * The player was handed a surface or a size.
   */
  Handed,

  /**
   * The player's surface went away.
   */
  Lost,
}

/**
 * What a surface that changed under the player needs from it.
 */
internal enum class SurfaceResize {
  /**
   * Nothing at all.
   */
  Leave,

  /**
   * Hand the output back, because media3 read the new size and dropped it.
   */
  Reapply,

  /**
   * Ask the graph to draw again into the buffer the surface now has.
   */
  Redraw,

  /**
   * Seek back to the slot the preview holds, so that slot is the next frame media3 draws.
   */
  Represent,
}

/**
 * What to do about a surface that changed, given whether a graph is up to draw it.
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
 * A graph handed a surface or a size starts its first frame over, and releases the frame it had queued behind the
 * one on screen. A playing preview is one frame further on and nobody sees it. One that is not playing shows the
 * slot after the one its playhead reports, and the frame it held has left media3's cache by then, so a redraw
 * repeats the wrong one. That is media3's limit, and only a seek back to the held slot draws it again. The seek is
 * made as the surface goes, so the held slot is what waits for the next one, and again where an output is handed
 * over with no warning.
 *
 * @param hasGraph Whether a graph has reached the point of being able to draw.
 * @param change What happened to the surface.
 * @param playing Whether the player is drawing frames on its own.
 */
internal fun surfaceChangeAction(
  hasGraph: Boolean,
  change: SurfaceChange,
  playing: Boolean,
): SurfaceResize =
  when (change) {
    SurfaceChange.Lost -> {
      if (hasGraph && !playing) SurfaceResize.Represent else SurfaceResize.Leave
    }
    SurfaceChange.Handed -> {
      when {
        !hasGraph -> SurfaceResize.Reapply
        playing -> SurfaceResize.Redraw
        else -> SurfaceResize.Represent
      }
    }
    SurfaceChange.Shape -> {
      if (hasGraph) SurfaceResize.Redraw else SurfaceResize.Reapply
    }
  }

/**
 * A millisecond next to [positionMs] to seek through on the way back to it.
 *
 * media3 does nothing for a seek to the millisecond a prepared player rests on, which is its limit. A seek to this
 * one first moves the player off [positionMs], so the seek back is one media3 carries out. Both are handed over in
 * one turn of the player's thread, so the playback thread reads the second before it decodes anything for the first.
 */
internal fun seekNudgeMs(positionMs: Long): Long = if (positionMs > 0) positionMs - 1 else positionMs + 1

/**
 * Whether a seek is handed to media3 through [seekNudgeMs] first.
 *
 * A paused player reports where it estimates it stopped, and media3 skips a seek by the millisecond it really rests
 * on. So a paused seek the report says media3 will carry out can still be skipped, and nothing is drawn for it. Going
 * through the millisecond beside the target rules that out. A scrub is left alone, since media3 draws every seek of
 * one and would show the nudge, and a seek that has to be carried out wherever the player rests always goes through.
 *
 * @param fresh Whether the seek has to be carried out even where media3 already rests on its target.
 * @param skipped Whether the player reports resting on the target, so the seek is answered without a frame.
 * @param playing Whether the player is drawing frames on its own.
 * @param scrubbing Whether media3 is in its scrubbing mode.
 */
internal fun seekGoesThroughNudge(
  fresh: Boolean,
  skipped: Boolean,
  playing: Boolean,
  scrubbing: Boolean,
): Boolean = fresh || (!skipped && !playing && !scrubbing)

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
