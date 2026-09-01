@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

// The wasm half. Arrays have to be copied rather than viewed, because wasm linear memory is not the
// JavaScript heap. Floats are copied one at a time, which stays cheap since the longest array a
// call site builds is a blurred fill's kernel weights. Bytes go over in latin-1 chunks, which
// costs one pass and no base64 inflation.

internal actual class JsOptions actual constructor() {
  private val target = newObject()

  actual fun put(
    key: String,
    value: String,
  ): JsOptions = apply { setString(target, key, value) }

  actual fun put(
    key: String,
    value: Int,
  ): JsOptions = apply { setDouble(target, key, value.toDouble()) }

  actual fun put(
    key: String,
    value: Double,
  ): JsOptions = apply { setDouble(target, key, value) }

  actual fun put(
    key: String,
    value: Boolean,
  ): JsOptions = apply { setBoolean(target, key, value) }

  actual fun put(
    key: String,
    value: JsAny?,
  ): JsOptions = apply { setAny(target, key, value) }

  actual fun build(): JsAny = target
}

internal actual fun jsArrayOf(value: JsAny): JsArray<JsAny> = singletonArray(value)

internal actual fun FloatArray.toFloat32Array(): Float32Array {
  val out = Float32Array(size)
  forEachIndexed { index, value -> setFloat(out, index, value) }
  return out
}

internal actual fun ByteArray.toUint8Array(): Uint8Array {
  val out = Uint8Array(size)
  var offset = 0
  val chunk = StringBuilder(BYTE_CHUNK)
  while (offset < size) {
    val end = minOf(offset + BYTE_CHUNK, size)
    chunk.setLength(0)
    for (index in offset until end) chunk.append((this[index].toInt() and BYTE_MASK).toChar())
    writeLatin1(out, offset, chunk.toString())
    offset = end
  }
  return out
}

internal actual fun Uint8Array.toByteArray(): ByteArray {
  val out = ByteArray(length)
  var offset = 0
  while (offset < length) {
    val end = minOf(offset + BYTE_CHUNK, length)
    val chunk = readLatin1(this, offset, end - offset)
    for (index in offset until end) out[index] = chunk[index - offset].code.toByte()
    offset = end
  }
  return out
}

internal actual fun hasVideoEncoder(): Boolean = js("typeof VideoEncoder !== 'undefined'")

internal actual fun hasAudioEncoder(): Boolean = js("typeof AudioEncoder !== 'undefined'")

internal actual fun hasAudioContext(): Boolean = js("typeof AudioContext !== 'undefined'")

@JsFun("() => ({})")
private external fun newObject(): JsAny

@JsFun("(target, key, value) => { target[key] = value; }")
private external fun setString(
  target: JsAny,
  key: String,
  value: String,
)

@JsFun("(target, key, value) => { target[key] = value; }")
private external fun setDouble(
  target: JsAny,
  key: String,
  value: Double,
)

@JsFun("(target, key, value) => { target[key] = value; }")
private external fun setBoolean(
  target: JsAny,
  key: String,
  value: Boolean,
)

@JsFun("(target, key, value) => { target[key] = value; }")
private external fun setAny(
  target: JsAny,
  key: String,
  value: JsAny?,
)

@JsFun("(value) => [value]")
private external fun singletonArray(value: JsAny): JsArray<JsAny>

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setFloat(
  array: Float32Array,
  index: Int,
  value: Float,
)

@JsFun("(array, offset, length) => String.fromCharCode.apply(null, array.subarray(offset, offset + length))")
private external fun readLatin1(
  array: Uint8Array,
  offset: Int,
  length: Int,
): String

@JsFun("(array, offset, text) => { for (let i = 0; i < text.length; i++) array[offset + i] = text.charCodeAt(i); }")
private external fun writeLatin1(
  array: Uint8Array,
  offset: Int,
  text: String,
)

private const val BYTE_CHUNK = 8192
private const val BYTE_MASK = 0xFF
