package dev.jordond.filmstrip.webcodecs.internal.failure

// A JavaScript error caught on js already is the Throwable, so the value is the error itself. Every exception Kotlin
// throws is an Exception or an Error, and a JavaScript one is neither.

internal actual fun jsValueOf(error: Throwable): JsAny? =
  if (error is Exception || error is Error) null else error.unsafeCast<JsAny>()

internal actual fun domExceptionNameOf(value: JsAny): String? {
  val v: dynamic = value
  return js("typeof DOMException !== 'undefined' && v instanceof DOMException ? v.name : null").unsafeCast<String?>()
}

internal actual fun jsCauseOf(value: JsAny): JsAny? {
  val v: dynamic = value
  return js("v.cause !== null && typeof v.cause === 'object' ? v.cause : null").unsafeCast<JsAny?>()
}

internal actual fun describeJs(value: JsAny): String {
  val v: dynamic = value
  return js("String(v)").unsafeCast<String>()
}
