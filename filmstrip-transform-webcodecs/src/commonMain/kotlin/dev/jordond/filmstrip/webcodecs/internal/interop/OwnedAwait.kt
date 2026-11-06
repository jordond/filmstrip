@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Awaits a promise whose value has to be released by whoever holds it, like a decoded sample.
 *
 * The caller owns the value this returns. A caller cancelled before it gets the value has already
 * gone, so [release] runs on the value instead, once the promise settles or straight after the
 * cancel if it already has. A rejection throws what `await` throws, and one that arrives after the
 * cancel is dropped.
 *
 * @param release Frees a value nobody is left to take.
 */
internal suspend fun <T : JsAny?> Promise<T>.awaitOwned(release: (T) -> Unit): T =
  try {
    await()
  } catch (cancelled: CancellationException) {
    then<JsAny?>(
      {
        release(it)
        null
      },
      { null },
    )
    throw cancelled
  }
