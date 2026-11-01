package dev.jordond.filmstrip.playback.internal

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Waits out the seeks an engine has handed to media3, the work queued on the player's thread and on media3's playback
 * thread, and everything that work launched on the engine.
 *
 * Every call the engine makes on `CompositionPlayer` is posted to [handler], and `CompositionPlayer` calls its
 * listeners on that same looper. What it asks of its sequence players is carried out on [playbackLooper], which hands
 * each state change back to the player's looper. So the echo goes through the player's thread, then the playback
 * thread, then the player's thread again, and each callback that came out of either hops onto [scope] as a job this
 * waits for. Both echoes on the player's thread are posted [Media3LoopRangeController.MIRROR_INTERVAL_MS] late, so a
 * playhead reading already scheduled runs ahead of them.
 *
 * A seek media3 has not answered yet is waited for by name, since its landing may still be inside media3. Work on a
 * codec's callback thread or the video graph's thread has not reached either looper yet and is not waited for.
 *
 * Every job on [scope] is waited for, so a held still's fallback reveal, which reports to the still's observers up to
 * a second after a swap that never draws, holds the fence that long.
 *
 * Engine's dispatcher only.
 *
 * @param scope The engine's scope, on the dispatcher everything a listener sees is confined to.
 * @param handler Posts onto the player's thread.
 * @param playbackLooper The looper media3 plays back on, or null while no player stands.
 * @param seekInFlight Whether a seek handed to media3 has not been answered yet.
 */
internal class Media3CallbackFence(
  private val scope: CoroutineScope,
  val handler: Handler,
  val playbackLooper: () -> Looper?,
  private val seekInFlight: () -> Boolean,
) {
  /**
   * Suspends until no seek is in flight, both threads have run what was queued on them and nothing the engine
   * launched is still running.
   *
   * A job that posts to either thread or issues a seek is caught by going round again, until a pass finds neither.
   * Never returns while a seek goes unanswered or playback runs, since the position ticker runs until it stops, so a
   * caller bounds the wait. An echo returns at once through a thread that has quit, as nothing more can come from it.
   */
  suspend fun awaitDrained() {
    val engine = checkNotNull(scope.coroutineContext[Job])
    do {
      echoThrough(handler, Media3LoopRangeController.MIRROR_INTERVAL_MS)
      playbackLooper()?.let { echoThrough(Handler(it), delayMs = 0) }
      echoThrough(handler, Media3LoopRangeController.MIRROR_INTERVAL_MS)
      val running = engine.children.toList()
      running.joinAll()
    } while (running.isNotEmpty() || seekInFlight())
  }

  private suspend fun echoThrough(
    target: Handler,
    delayMs: Long,
  ) {
    suspendCancellableCoroutine { echoed ->
      val sentinel = Runnable { echoed.resume(Unit) }
      if (!target.postDelayed(sentinel, delayMs)) echoed.resume(Unit)
      echoed.invokeOnCancellation { target.removeCallbacks(sentinel) }
    }
  }
}
