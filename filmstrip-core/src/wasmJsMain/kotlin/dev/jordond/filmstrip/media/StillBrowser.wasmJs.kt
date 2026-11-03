@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi

// The wasm half. Bytes have to be copied rather than viewed, because wasm linear memory is not the
// JavaScript heap. They go over in latin-1 chunks, which costs one pass and no base64 inflation.

internal actual fun imageDataOf(
  pixels: ByteArray,
  width: Int,
  height: Int,
): ImageData {
  val image = newImageData(width, height)
  pixels.forEachLatin1Chunk { offset, text -> writeImageLatin1(image, offset, text) }
  return image
}

internal actual fun blobOptions(
  type: String,
  quality: Double,
): JsAny = newBlobOptions(type, quality)

@InternalFilmstripApi
public actual fun blobOf(
  bytes: ByteArray,
  type: String,
): Blob {
  val array = newByteArray(bytes.size)
  bytes.forEachLatin1Chunk { offset, text -> writeArrayLatin1(array, offset, text) }
  return newBlob(array, type)
}

@InternalFilmstripApi
public actual fun ArrayBuffer.toByteArray(): ByteArray {
  val view = viewOf(this)
  return latin1BytesOf(byteLength) { offset, length -> readLatin1(view, offset, length) }
}

/**
 * Hands this array to [write] as latin-1 text, a chunk at a time, with the offset each chunk's first byte sits at.
 *
 * Each character of a chunk is one byte, so a `@JsFun` on the other side writes it back out with `charCodeAt`.
 */
@InternalFilmstripApi
public fun ByteArray.forEachLatin1Chunk(write: (offset: Int, text: String) -> Unit) {
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

/**
 * An array of [size] bytes read back as latin-1 text, a chunk at a time, from whatever [read] returns for the offset
 * and the length of each chunk.
 *
 * The other half of [forEachLatin1Chunk], for a `@JsFun` that reads bytes out with `String.fromCharCode`.
 */
@InternalFilmstripApi
public fun latin1BytesOf(
  size: Int,
  read: (offset: Int, length: Int) -> String,
): ByteArray {
  val out = ByteArray(size)
  var offset = 0
  while (offset < size) {
    val end = minOf(offset + BYTE_CHUNK, size)
    val chunk = read(offset, end - offset)
    for (index in offset until end) out[index] = chunk[index - offset].code.toByte()
    offset = end
  }
  return out
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
