@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

internal actual fun rejectedWithDomException(
  name: String,
  message: String,
): Promise<JsAny?> = js("Promise.reject(new DOMException(message, name))")

internal actual fun rejectedWithCause(
  message: String,
  causeName: String,
  causeMessage: String,
): Promise<JsAny?> = js("Promise.reject(new Error(message, { cause: new DOMException(causeMessage, causeName) }))")

internal actual fun throwDomException(
  name: String,
  message: String,
): Unit = js("{ throw new DOMException(message, name); }")
