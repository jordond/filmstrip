package dev.jordond.filmstrip.player

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.concurrent.AtomicInt

/**
 * A block posted to a dispatch queue that holds that queue until the test opens it.
 *
 * A test orders work across queues by when it opens each gate, so nothing depends on how long a block sleeps or how
 * soon the scheduler gets to it. A gate the test never opens lets its queue go after [GATE_LIMIT_NANOS], so a test
 * that fails first does not hold the queue for the rest of the run.
 */
@OptIn(ExperimentalForeignApi::class)
internal class QueueGate {
  private val semaphore = dispatch_semaphore_create(0)
  private val opened = AtomicInt(0)

  /**
   * Posts a block to [queue] that waits for [open] and then runs [then].
   */
  fun hold(
    queue: dispatch_queue_t,
    then: () -> Unit = {},
  ) {
    dispatch_async(queue) {
      dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, GATE_LIMIT_NANOS))
      then()
    }
  }

  /**
   * Lets the held block go on. Opening a gate that is already open does nothing.
   */
  fun open() {
    if (opened.compareAndSet(0, 1)) dispatch_semaphore_signal(semaphore)
  }

  private companion object {
    const val GATE_LIMIT_NANOS = 20_000_000_000L
  }
}
