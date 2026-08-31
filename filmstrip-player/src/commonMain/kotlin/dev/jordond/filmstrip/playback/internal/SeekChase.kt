package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.player.SeekAccuracy
import kotlin.time.Duration

/**
 * Issues one platform seek and calls [onComplete] once it finishes.
 *
 * The whole of what a backend hands [SeekChase]. Cancelling a seek that is still running when a
 * newer one arrives belongs to the platform, and both `AVPlayer` and `CompositionPlayer` already do
 * it, so a cancelled seek's [onComplete] may never arrive and [SeekChase] never waits for it.
 */
internal fun interface PlatformSeek {
  /**
   * Seeks to [position] and calls [onComplete] when the landing frame is presented.
   */
  fun seek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  )
}

/**
 * How one request to [SeekChase.request] finished.
 */
internal enum class SeekResolution {
  /**
   * A platform seek ran for this request and its frame is presented.
   */
  Landed,

  /**
   * A newer request replaced this one, or the chase was released, before the platform reached it.
   */
  Superseded,
}

/**
 * Coalesces a burst of seek requests into at most one platform seek at a time.
 *
 * Requests arrive faster than any decoder settles, so only the newest is worth running: an older
 * one's landing frame is thrown away the moment the next request arrives. The chase holds the
 * newest target, dispatches it once the previous platform seek finishes, and drops everything in
 * between.
 *
 * Dropping a request is not the same as forgetting it. Every call to [request] resolves through
 * [onResolved] exactly once, as [SeekResolution.Landed] when the platform ran it and
 * [SeekResolution.Superseded] when it did not, so a caller counting completions against requests
 * never waits for one that will not come.
 *
 * Not thread safe. Confine it to the engine's own dispatcher, the same one platform callbacks are
 * posted to.
 *
 * @param platformSeek How to run one seek.
 * @param isReady Whether the platform can seek yet. A request made before it can waits, and
 *   [onReady] releases it.
 * @param onResolved Called once per [request]. A thrown-away request resolves the moment it is
 *   replaced, so resolutions do not arrive in request order.
 */
internal class SeekChase(
  private val platformSeek: PlatformSeek,
  private val isReady: () -> Boolean,
  private val onResolved: (position: Duration, resolution: SeekResolution) -> Unit,
) {
  private var chaseTime: SeekRequest? = null
  private var inFlight: SeekRequest? = null

  /**
   * True while any request is outstanding, which is what [dev.jordond.filmstrip.player.PlayerState]
   * reports as seeking.
   */
  val isSeeking: Boolean get() = isSeekInProgress || chaseTime != null

  private val isSeekInProgress: Boolean get() = inFlight != null

  /**
   * Takes a seek request, superseding whichever request was still waiting to be dispatched.
   */
  fun request(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    // Install before resolving, the way release() and the completion callback do. Resolving first
    // would let a listener that seeks from inside the callback see the superseded request still
    // sitting in chaseTime, resolve it twice, and have its own request overwritten unresolved.
    val superseded = chaseTime
    chaseTime = SeekRequest(position, accuracy)
    superseded?.let { onResolved(it.position, SeekResolution.Superseded) }
    trySeekToChaseTime()
  }

  /**
   * Dispatches a request that was waiting on [isReady].
   */
  fun onReady() {
    trySeekToChaseTime()
  }

  /**
   * Resolves everything outstanding as superseded and stops chasing.
   *
   * Call it when the platform can no longer answer: a release, a failure, or a rebuild that makes
   * the old positions meaningless. A late callback for a seek that was in flight is ignored.
   */
  fun release() {
    val outstanding = listOfNotNull(inFlight, chaseTime)
    inFlight = null
    chaseTime = null
    outstanding.forEach { onResolved(it.position, SeekResolution.Superseded) }
  }

  private fun trySeekToChaseTime() {
    if (isSeekInProgress) return
    val request = chaseTime ?: return
    if (!isReady()) return

    chaseTime = null
    inFlight = request
    platformSeek.seek(request.position, request.accuracy) {
      // Identity, not a boolean: it rejects a second callback for the same seek and a callback
      // that arrives after release() already synthesised this request's resolution.
      if (inFlight === request) {
        inFlight = null
        onResolved(request.position, SeekResolution.Landed)
        trySeekToChaseTime()
      }
    }
  }
}

private class SeekRequest(
  val position: Duration,
  val accuracy: SeekAccuracy,
)
