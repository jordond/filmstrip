@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

// The js half. A Kotlin FloatArray already is a Float32Array here and a ByteArray already is an
// Int8Array, so both conversions are a view rather than a copy.

internal actual class JsOptions actual constructor() {
  private val target: dynamic = js("({})")

  actual fun put(
    key: String,
    value: String,
  ): JsOptions = apply { target[key] = value }

  actual fun put(
    key: String,
    value: Int,
  ): JsOptions = apply { target[key] = value }

  actual fun put(
    key: String,
    value: Double,
  ): JsOptions = apply { target[key] = value }

  actual fun put(
    key: String,
    value: Boolean,
  ): JsOptions = apply { target[key] = value }

  actual fun put(
    key: String,
    value: JsAny?,
  ): JsOptions = apply { target[key] = value }

  actual fun build(): JsAny = target.unsafeCast<JsAny>()
}

internal actual fun jsArrayOf(value: JsAny): JsArray<JsAny> = js("[value]").unsafeCast<JsArray<JsAny>>()

internal actual fun jsArrayOf(values: List<JsAny>): JsArray<JsAny> {
  val out = js("[]")
  values.forEach { out.push(it) }
  return out.unsafeCast<JsArray<JsAny>>()
}

internal actual fun jsCopyWith(
  source: JsAny,
  key: String,
  value: String,
): JsAny = js("Object.assign({}, source, { [key]: value })").unsafeCast<JsAny>()

internal actual fun FloatArray.toFloat32Array(): Float32Array = unsafeCast<Float32Array>()

// A `js()` body reaches the enclosing function's locals and parameters by name. The receiver is
// emitted under a mangled name such as `_this__u8e3s4`, so the local is what gives the snippet
// something to spell.
internal actual fun ByteArray.toUint8Array(): Uint8Array {
  val view = this
  return js("new Uint8Array(view.buffer, view.byteOffset, view.length)").unsafeCast<Uint8Array>()
}

internal actual fun Uint8Array.toByteArray(): ByteArray {
  val view = this
  return js("new Int8Array(view.buffer.slice(view.byteOffset, view.byteOffset + view.length))").unsafeCast<ByteArray>()
}

internal actual fun hasVideoEncoder(): Boolean = js("typeof VideoEncoder !== 'undefined'").unsafeCast<Boolean>()

internal actual fun hasAudioEncoder(): Boolean = js("typeof AudioEncoder !== 'undefined'").unsafeCast<Boolean>()

internal actual fun hasAudioContext(): Boolean = js("typeof AudioContext !== 'undefined'").unsafeCast<Boolean>()
