package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size

/**
 * An opaque RGBA PNG of [size], its deflate stream left stored rather than compressed, with [colour] written between
 * its header and its image data.
 *
 * Every channel is white at eight bits, or [sixteenBitSample] at sixteen bits with alpha opaque when one is given.
 * [texels] paints each pixel instead, eight-bit RGBA row by row from the top left.
 */
internal fun pngOf(
  size: Size,
  colour: ByteArray = ByteArray(0),
  sixteenBitSample: Int? = null,
  texels: List<ByteArray>? = null,
): ByteArray {
  val texel =
    if (sixteenBitSample == null) {
      ByteArray(4) { 0xFF.toByte() }
    } else {
      val sample = byteArrayOf((sixteenBitSample ushr 8).toByte(), sixteenBitSample.toByte())
      sample + sample + sample + byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    }
  val rows =
    (0 until size.height).fold(ByteArray(0)) { image, y ->
      image + byteArrayOf(0) +
        (0 until size.width).fold(ByteArray(0)) { line, x -> line + (texels?.get(y * size.width + x) ?: texel) }
    }

  val depth: Byte = if (sixteenBitSample == null) 8 else 16
  val header =
    int32(size.width) + int32(size.height) + byteArrayOf(depth, 6, 0, 0, 0)
  val zlib =
    byteArrayOf(0x78, 0x01, 0x01) +
      int16LittleEndian(rows.size) +
      int16LittleEndian(rows.size.inv() and 0xFFFF) +
      rows +
      int32(adler32(rows))

  return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
    chunk("IHDR", header) +
    colour +
    chunk("IDAT", zlib) +
    chunk("IEND", ByteArray(0))
}

/**
 * The signature and `IHDR` of a [width] by [height] RGBA PNG at [depth] bits, with no image data after them.
 */
internal fun pngHeaderOnly(
  width: Int,
  height: Int,
  depth: Int,
): ByteArray =
  byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
    chunk("IHDR", int32(width) + int32(height) + byteArrayOf(depth.toByte(), 6, 0, 0, 0))

/**
 * A big-endian EXIF block holding one tag, the orientation [value].
 *
 * It is laid out the way an `eXIf` chunk carries it, a TIFF header and one directory with no next one.
 */
internal fun exifOrientation(value: Int): ByteArray =
  "MM".encodeToByteArray() +
    byteArrayOf(0, 42) +
    int32(8) +
    byteArrayOf(0, 1) +
    byteArrayOf(0x01, 0x12, 0, 3) +
    int32(1) +
    byteArrayOf((value ushr 8).toByte(), value.toByte(), 0, 0) +
    int32(0)

/**
 * A PNG chunk of [type] holding [data], led by its length and closed by its CRC.
 */
internal fun chunk(
  type: String,
  data: ByteArray,
): ByteArray {
  val typed = type.encodeToByteArray() + data
  return int32(data.size) + typed + int32(crc32(typed))
}

private fun int32(value: Int): ByteArray =
  byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

private fun int16LittleEndian(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun crc32(bytes: ByteArray): Int {
  var crc = -1
  for (byte in bytes) {
    crc = crc xor (byte.toInt() and 0xFF)
    repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
  }
  return crc.inv()
}

private fun adler32(bytes: ByteArray): Int {
  var a = 1
  var b = 0
  for (byte in bytes) {
    a = (a + (byte.toInt() and 0xFF)) % 65521
    b = (b + a) % 65521
  }
  return (b shl 16) or a
}
