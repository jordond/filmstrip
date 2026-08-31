package dev.jordond.filmstrip.thumbnail

import dev.jordond.filmstrip.Cancellable

/**
 * Walks a run of requests through a source one at a time, which is what
 * [ThumbnailSource.requestThumbnails] does for a source that overrides nothing.
 *
 * The next request is asked for once the one before it has been delivered, so a source sees the
 * same one-outstanding-request shape it would from a caller driving it itself.
 *
 * A frame that arrives after the run was cancelled is closed here, since the caller that would have
 * owned it has gone.
 */
internal class SerialThumbnailRun(
  private val source: ThumbnailSource,
  private val requests: List<ThumbnailRequest>,
  private val callback: ThumbnailBatchCallback,
) : Cancellable {
  private var cancelled = false
  private var handle: Cancellable? = null
  private var next = 0
  private var pumping = false
  private var deliveredWhilePumping = false

  init {
    pump()
  }

  override fun cancel() {
    if (cancelled) return
    cancelled = true
    handle?.cancel()
    handle = null
  }

  /**
   * Asks for requests until one of them has to be waited on.
   *
   * Iterative rather than recursive, so a source that answers on the thread that asked serves a
   * long strip on one frame of stack rather than one per entry.
   */
  private fun pump() {
    if (pumping) return
    pumping = true
    try {
      while (!cancelled && next < requests.size) {
        deliveredWhilePumping = false
        val index = next
        // The request this replaces has already delivered, and its handle is released the way a
        // cancelled one would be: [ThumbnailSource] frees what one request held through cancel()
        // however that request ended. Released after the next is issued, so a source holding
        // anything across the run is never left with nothing outstanding.
        val previous = handle
        handle = source.requestThumbnail(requests[index]) { result -> deliver(index, result) }
        previous?.cancel()
        if (!deliveredWhilePumping) return
      }

      // The run is over, so the request that ended it is released like every one before it.
      handle?.cancel()
      handle = null
    } finally {
      pumping = false
    }
  }

  private fun deliver(
    index: Int,
    result: ThumbnailResult,
  ) {
    if (cancelled) {
      (result as? ThumbnailResult.Success)?.image?.close()
      return
    }

    next = index + 1
    callback.onThumbnail(index, result)

    if (pumping) {
      deliveredWhilePumping = true
      return
    }
    pump()
  }
}
