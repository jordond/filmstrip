package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.geometry.Size

/**
 * A lossless WebP of [size] holding one color, [argb], at every texel.
 *
 * It is a VP8L image whose five prefix codes each hold one symbol, so no texel stores a bit. The color is whatever the
 * codes name, so a straight alpha is stored as written.
 */
internal fun flatLosslessWebp(
  size: Size,
  argb: Int,
): ByteArray {
  var bits = 0L
  var count = 0
  val out = mutableListOf<Byte>()

  fun put(
    value: Int,
    width: Int,
  ) {
    bits = bits or ((value.toLong() and ((1L shl width) - 1)) shl count)
    count += width
    while (count >= 8) {
      out += bits.toByte()
      bits = bits ushr 8
      count -= 8
    }
  }
  put(0x2F, 8)
  put(size.width - 1, 14)
  put(size.height - 1, 14)
  put(1, 1)
  put(0, 3)
  put(0, 3)
  for (symbol in listOf(argb shr 8, argb shr 16, argb, argb ushr 24, 0)) {
    put(1, 1)
    put(0, 1)
    put(1, 1)
    put(symbol, 8)
  }
  if (count > 0) out += bits.toByte()
  val stream = out.toByteArray()

  fun le32(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }
  val padded = if (stream.size % 2 == 1) stream + byteArrayOf(0) else stream
  val chunk = "VP8L".encodeToByteArray() + le32(stream.size) + padded
  return "RIFF".encodeToByteArray() + le32(4 + chunk.size) + "WEBP".encodeToByteArray() + chunk
}
