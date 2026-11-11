package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.Composition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Draws the frame a preview is holding again, once every interval, for as long as it is asked to.
 *
 * A paused preview shows one frame and draws nothing new. An effect that says `redrawsWhileHeld` moves with the
 * preview's clock instead of the playhead, so the held frame is drawn again each time a frame would have been shown.
 *
 * @param scope Where the redraws run, on the dispatcher the engine is confined to.
 * @param redraw Draws the held frame again.
 */
internal class HeldRedraw(
  private val scope: CoroutineScope,
  private val redraw: () -> Unit,
) {
  private var job: Job? = null
  private var running: Duration? = null

  /**
   * Redraws every [interval] from now on, or stops when it is null. An interval already running carries on.
   */
  fun sync(interval: Duration?) {
    if (interval == running && (interval == null || job?.isActive == true)) return
    job?.cancel()
    running = interval
    job =
      interval?.let { every ->
        scope.launch {
          while (true) {
            delay(every)
            redraw()
          }
        }
      }
  }
}

/**
 * Whether any effect that draws in this edit asks a preview to keep drawing a held frame.
 */
internal fun Composition.redrawsWhileHeld(): Boolean =
  (effects + tracks.flatMap { track -> track.effects + track.clips.flatMap { it.effects } })
    .any { it.redrawsWhileHeld && !it.isInert }
