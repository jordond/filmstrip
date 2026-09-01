@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

// The js half. A Kotlin ByteArray already is an Int8Array here, so every conversion is a view over
// the same buffer rather than a copy.
//
// A `js()` body reaches the enclosing function's locals and parameters by name. The receiver is
// emitted under a mangled name, so a local is what gives the snippet something to spell.

internal actual fun imageDataOf(
  pixels: ByteArray,
  width: Int,
  height: Int,
): ImageData {
  val view = pixels
  return js("new ImageData(new Uint8ClampedArray(view.buffer, view.byteOffset, view.length), width, height)")
    .unsafeCast<ImageData>()
}

internal actual fun blobOptions(
  type: String,
  quality: Double,
): JsAny = js("({ type: type, quality: quality })").unsafeCast<JsAny>()

internal actual fun blobOf(
  bytes: ByteArray,
  type: String,
): Blob {
  val view = bytes
  return js("new Blob([new Uint8Array(view.buffer, view.byteOffset, view.length)], { type: type })")
    .unsafeCast<Blob>()
}

internal actual fun ArrayBuffer.toByteArray(): ByteArray {
  val buffer = this
  return js("new Int8Array(buffer.slice(0))").unsafeCast<ByteArray>()
}
