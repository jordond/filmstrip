@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.failure

// On wasm a JavaScript value reaches Kotlin wrapped in a JsException, whether a call threw it or a promise rejected
// with it through the module's own await.

internal actual fun jsValueOf(error: Throwable): JsAny? = (error as? JsException)?.thrownValue

internal actual fun domExceptionNameOf(value: JsAny): String? =
  js("typeof DOMException !== 'undefined' && value instanceof DOMException ? value.name : null")

internal actual fun jsCauseOf(value: JsAny): JsAny? =
  js("value.cause !== null && typeof value.cause === 'object' ? value.cause : null")

internal actual fun describeJs(value: JsAny): String = js("String(value)")
