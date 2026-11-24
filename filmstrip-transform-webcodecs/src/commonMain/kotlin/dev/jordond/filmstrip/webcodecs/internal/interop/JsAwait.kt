@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Awaits a promise and throws what it rejected with.
 *
 * A rejection the browser raised keeps its JavaScript value on both targets, so a failure can read the
 * `DOMException` name and the `cause` chain off it. A JavaScript error already is a [Throwable] on js, and on wasm it
 * arrives as a `JsException` holding the value.
 */
internal expect suspend fun <T : JsAny?> Promise<T>.await(): T
