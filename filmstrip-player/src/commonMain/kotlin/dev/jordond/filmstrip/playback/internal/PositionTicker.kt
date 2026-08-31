package dev.jordond.filmstrip.playback.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Polls the platform playhead while pixels are moving.
 *
 * No backend pushes a position on its own, so the engine reads one on a timer. This runs at roughly
 * display rate and [dev.jordond.filmstrip.player.VideoPlayer.positionFlow] snaps the result down to
 * whatever grid a collector asked for, so the rate here is the ceiling rather than the contract.
 *
 * Not thread safe. Confine it to the engine's own dispatcher.
 *
 * @param scope Where the loop runs.
 * @param interval How long to wait between reads.
 * @param read Reads the platform's current position.
 * @param emit Receives every read, starting with one at [start].
 */
internal class PositionTicker(
  private val scope: CoroutineScope,
  private val interval: Duration,
  private val read: () -> Duration,
  private val emit: (Duration) -> Unit,
) {
  private var job: Job? = null

  /**
   * Starts reading, emitting once straight away. Does nothing when already running.
   */
  fun start() {
    if (job?.isActive == true) return
    job =
      scope.launch {
        while (isActive) {
          emit(read())
          delay(interval)
        }
      }
  }

  /**
   * Stops reading. Does nothing when already stopped.
   */
  fun stop() {
    job?.cancel()
    job = null
  }
}
