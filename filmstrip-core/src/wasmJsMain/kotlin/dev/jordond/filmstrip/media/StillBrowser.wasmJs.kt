@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

// The wasm half. Bytes have to be copied rather than viewed, because wasm linear memory is not the
// JavaScript heap. They go over in latin-1 chunks, which costs one pass and no base64 inflation.

internal actual fun imageDataOf(
  pixels: ByteArray,
  width: Int,
  height: Int,
): ImageData {
  val image = newImageData(width, height)
  pixels.copyInChunks { offset, text -> writeImageLatin1(image, offset, text) }
  return image
}

internal actual fun blobOptions(
  type: String,
  quality: Double,
): JsAny = newBlobOptions(type, quality)

internal actual fun blobOf(
  bytes: ByteArray,
  type: String,
): Blob {
  val array = newByteArray(bytes.size)
  bytes.copyInChunks { offset, text -> writeArrayLatin1(array, offset, text) }
  return newBlob(array, type)
}

internal actual fun ArrayBuffer.toByteArray(): ByteArray {
  val view = viewOf(this)
  val out = ByteArray(byteLength)
  var offset = 0
  while (offset < out.size) {
    val end = minOf(offset + BYTE_CHUNK, out.size)
    val chunk = readLatin1(view, offset, end - offset)
    for (index in offset until end) out[index] = chunk[index - offset].code.toByte()
    offset = end
  }
  return out
}

private inline fun ByteArray.copyInChunks(write: (Int, String) -> Unit) {
  var offset = 0
  val chunk = StringBuilder(BYTE_CHUNK)
  while (offset < size) {
    val end = minOf(offset + BYTE_CHUNK, size)
    chunk.setLength(0)
    for (index in offset until end) chunk.append((this[index].toInt() and BYTE_MASK).toChar())
    write(offset, chunk.toString())
    offset = end
  }
}

@JsFun("(width, height) => new ImageData(width, height)")
private external fun newImageData(
  width: Int,
  height: Int,
): ImageData

@JsFun(
  "(image, offset, text) => { const d = image.data; for (let i = 0; i < text.length; i++) d[offset + i] = text.charCodeAt(i); }",
)
private external fun writeImageLatin1(
  image: ImageData,
  offset: Int,
  text: String,
)

@JsFun("(type, quality) => ({ type: type, quality: quality })")
private external fun newBlobOptions(
  type: String,
  quality: Double,
): JsAny

@JsFun("(length) => new Uint8Array(length)")
private external fun newByteArray(length: Int): JsAny

@JsFun("(array, offset, text) => { for (let i = 0; i < text.length; i++) array[offset + i] = text.charCodeAt(i); }")
private external fun writeArrayLatin1(
  array: JsAny,
  offset: Int,
  text: String,
)

@JsFun("(array, type) => new Blob([array], { type: type })")
private external fun newBlob(
  array: JsAny,
  type: String,
): Blob

@JsFun("(buffer) => new Uint8Array(buffer)")
private external fun viewOf(buffer: ArrayBuffer): JsAny

@JsFun("(array, offset, length) => String.fromCharCode.apply(null, array.subarray(offset, offset + length))")
private external fun readLatin1(
  array: JsAny,
  offset: Int,
  length: Int,
): String

private const val BYTE_CHUNK = 8192

private const val BYTE_MASK = 0xFF
