package dev.jordond.filmstrip.playback.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t
import kotlin.coroutines.resume

/**
 * Waits out the seeks an engine has issued and the callbacks AVFoundation has posted to it.
 *
 * Measured on the macOS host and the iOS simulator alike. AVFoundation answers a finished seek on the main queue, and a
 * seek a newer one replaced on the thread issuing the newer one, inside that call. The item's time jump notification
 * and a change AVFoundation makes to the player by itself, like playback stopping at the item's end, arrive on the main
 * queue. A play or pause the engine or the host calls posts its rate change and key-value change on the calling thread
 * before the call returns. The engine's time observers run on [callbackQueue], and in all seven seeks measured on each
 * the periodic one ran for the seek's jump before the seek was answered. Every one of those hops onto [scope] before it
 * touches the engine.
 *
 * The item's end, stall and failure notifications were not measured, and the audio session posts its interruptions
 * and route changes on a thread of its own on iOS. One already posted has hopped onto [scope] and is covered. One
 * still being delivered on another thread is not, and nothing here can wait for it.
 *
 * Engine's dispatcher only.
 *
 * @param scope The engine's scope, on the dispatcher everything is confined to.
 * @param callbackQueue The queue AVFoundation calls the engine's time observers on.
 * @param seeks The engine's seeks, which count every seek handed to the item until it is answered.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvCallbackFence(
  private val scope: CoroutineScope,
  private val callbackQueue: dispatch_queue_t,
  private val seeks: AvSeekCoordinator,
) {
  /**
   * How many seeks handed to the item have not been answered yet.
   */
  val unansweredSeeks: Int get() = seeks.unansweredSeeks

  /**
   * Suspends until every seek counted has been answered and every callback already posted has run on [scope].
   *
   * Waits for the seeks still on their way, echoes through [callbackQueue] and the main queue, then hops onto [scope]
   * behind whatever those queued. A callback that issues another seek while this runs is caught by going round again,
   * until a pass hands no seek over. A seek the chase is holding until the item can seek has not been handed over and
   * is not waited for. Never returns while a seek goes unanswered or the main queue is not served, which includes
   * calling this on the main thread, so a caller bounds the wait.
   */
  suspend fun awaitDrained() {
    do {
      val counted = seeks.countedSeeks
      seeks.awaitAnswered()
      echoThrough(callbackQueue)
      echoThrough(dispatch_get_main_queue())
      scope.launch { }.join()
    } while (seeks.countedSeeks != counted)
  }

  private suspend fun echoThrough(queue: dispatch_queue_t) {
    suspendCancellableCoroutine { echoed -> dispatch_async(queue) { echoed.resume(Unit) } }
  }
}
