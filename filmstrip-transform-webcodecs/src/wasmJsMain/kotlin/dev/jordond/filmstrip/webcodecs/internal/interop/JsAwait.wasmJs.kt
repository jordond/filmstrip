@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import dev.jordond.filmstrip.webcodecs.internal.failure.describeJs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

internal actual suspend fun <T : JsAny?> Promise<T>.await(): T =
  suspendCancellableCoroutine { continuation ->
    then(
      onFulfilled = {
        continuation.resume(it)
        null
      },
      onRejected = {
        continuation.resumeWithException(it.toThrowableOrNull() ?: Exception(describeJs(it)))
        null
      },
    )
  }
