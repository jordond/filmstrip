package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.Promise
import kotlinx.coroutines.await as awaitPromise

internal actual suspend fun <T : JsAny?> Promise<T>.await(): T = awaitPromise()
