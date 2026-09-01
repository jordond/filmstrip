package dev.jordond.filmstrip.compose.ui.interaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Settles an [ArrowScrubGesture] a short idle period after its last repeat.
 *
 * A `KeyUp` alone is not enough to end a burst: it can be missed when focus moves away mid-press, and some hosts
 * never deliver one at all. This is the fallback that ends the gesture anyway, leaving a scrub relaxed a little
 * longer rather than leaving it relaxed forever.
 *
 * Split from the key handler that drives it for the same reason [ArrowScrubGesture] is: the countdown is a plain
 * timer rather than something only a real held key can exercise.
 */
internal class ArrowIdleSettle(
  private val scope: CoroutineScope,
  private val gesture: ArrowScrubGesture,
  private val timeout: Duration = IDLE_TIMEOUT,
) {
  private var job: Job? = null

  /**
   * Restarts the idle countdown, called on every repeat.
   */
  fun ping() {
    job?.cancel()
    job =
      scope.launch {
        delay(timeout)
        gesture.end()
      }
  }

  /**
   * Drops the countdown without settling, called once a `KeyUp` settles the gesture itself.
   */
  fun cancel() {
    job?.cancel()
    job = null
  }

  internal companion object {
    // Comfortably above a real keyboard's repeat interval, roughly 33 to 40 milliseconds, with
    // enough margin that a burst never settles between two of its own repeats. The fallback only
    // matters once a KeyUp is missed, so settling a little late there costs nothing a user notices.
    val IDLE_TIMEOUT = 200.milliseconds
  }
}
