package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlinx.coroutines.Job

/**
 * The one outstanding `setComposition` load, so a later request can supersede it exactly once.
 *
 * Each load's caller hears one result, whichever of settling, superseding or cancelling reaches it first. Engine's
 * dispatcher only.
 */
internal class PendingLoad {
  private var pending: Load? = null

  /**
   * Records [job] as the load outstanding now, answered through [callback].
   *
   * @return a handle that cancels the load without answering its caller.
   */
  fun track(
    job: Job,
    callback: SetCompositionCallback,
  ): Cancellable {
    pending = Load(job, callback)
    return Cancellable {
      pending?.takeIf { it.job === job }?.let { it.settled = true }
      job.cancel()
    }
  }

  /**
   * Answers the outstanding load's caller with [result], unless that load was already answered or cancelled.
   */
  fun settle(result: SetCompositionResult) {
    val load = pending ?: return
    if (load.settled) return
    load.settled = true
    pending = null
    load.callback.onResult(result)
  }

  /**
   * Cancels the load outstanding now and tells its caller it was superseded.
   */
  fun supersede() {
    val load = pending ?: return
    pending = null
    if (load.settled) return
    load.settled = true
    load.job.cancel()
    load.callback.onResult(SetCompositionResult.Superseded)
  }

  private class Load(
    val job: Job,
    val callback: SetCompositionCallback,
  ) {
    var settled: Boolean = false
  }
}
