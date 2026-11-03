package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Holds the one answer [probe] gives, for a fact about the device every driver in the process shares.
 *
 * The first caller runs [probe] on [Dispatchers.Default] and every later one reads the same answer. A lock stops two
 * racing first calls from both probing. The answer is kept as soon as [probe] returns, so a probe that finished is
 * kept even when the caller that paid for it is cancelled.
 *
 * @param probe Reads the answer, at whatever cost.
 */
@InternalFilmstripApi
public class ProcessProbe<T : Any>(
  private val probe: suspend () -> T,
) {
  private val lock = Mutex()

  @Volatile
  private var probed: T? = null

  /**
   * The answer, from the first probe this process ran or from running it now.
   */
  public suspend fun read(): T =
    probed ?: lock.withLock {
      probed ?: withContext(Dispatchers.Default) { probe().also { probed = it } }
    }
}
