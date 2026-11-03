package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block], which awaits a browser promise, and wraps what it gives or throws in a [Result].
 *
 * A rejected promise and anything else [block] throws come back as a failure for the caller to answer with its own
 * default. A cancellation is thrown on rather than wrapped, so the coroutine awaiting the promise still stops.
 */
@InternalFilmstripApi
public inline fun <T> awaitCatching(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (rejected: Throwable) {
    Result.failure(rejected)
  }
