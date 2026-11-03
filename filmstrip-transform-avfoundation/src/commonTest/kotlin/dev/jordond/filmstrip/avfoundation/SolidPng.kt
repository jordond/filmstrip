package dev.jordond.filmstrip.avfoundation

/**
 * An opaque PNG of [width] by [height] pixels, every one of them [color].
 *
 * Written with no color chunk and stored rather than compressed, so the file holds [color]'s codes
 * exactly and nothing in it names a color space.
 */
internal fun solidPng(
  width: Int,
  height: Int,
  color: Triple<Int, Int, Int>,
): ByteArray {
  val row = byteArrayOf(0) + ByteArray(width * RGB) { byteOf(color, it % RGB) }
  val pixels = ByteArray(0).let { rows -> (0 until height).fold(rows) { all, _ -> all + row } }
  val header = bigEndian(width) + bigEndian(height) + byteArrayOf(BIT_DEPTH, TRUE_COLOR, 0, 0, 0)

  return SIGNATURE + chunk("IHDR", header) + chunk("IDAT", stored(pixels)) + chunk("IEND", ByteArray(0))
}

private fun byteOf(
  color: Triple<Int, Int, Int>,
  channel: Int,
): Byte =
  when (channel) {
    0 -> color.first
    1 -> color.second
    else -> color.third
  }.toByte()

// A zlib stream of one stored block, which only holds up to 65535 bytes.
private fun stored(data: ByteArray): ByteArray {
  require(data.size <= MAX_STORED) { "a ${data.size} byte image does not fit one stored block" }
  val length = data.size
  val inverse = length.inv() and MAX_STORED
  val block =
    byteArrayOf(
      1,
      (length and BYTE).toByte(),
      (length shr BITS and BYTE).toByte(),
      (inverse and BYTE).toByte(),
      (inverse shr BITS and BYTE).toByte(),
    )
  return byteArrayOf(ZLIB_METHOD, ZLIB_FLAGS) + block + data + bigEndian(adler32(data))
}

private fun chunk(
  type: String,
  data: ByteArray,
): ByteArray {
  val named = type.encodeToByteArray() + data
  return bigEndian(data.size) + named + bigEndian(crc32(named))
}

private fun bigEndian(value: Int): ByteArray = ByteArray(Int.SIZE_BYTES) { (value shr (BITS * (3 - it))).toByte() }

private fun adler32(data: ByteArray): Int {
  var a = 1
  var b = 0
  data.forEach { byte ->
    a = (a + (byte.toInt() and BYTE)) % ADLER_MOD
    b = (b + a) % ADLER_MOD
  }
  return (b shl ADLER_SHIFT) or a
}

private fun crc32(data: ByteArray): Int {
  var crc = -1
  data.forEach { byte ->
    crc = crc xor (byte.toInt() and BYTE)
    repeat(BITS) { crc = if (crc and 1 != 0) (crc ushr 1) xor CRC_POLYNOMIAL else crc ushr 1 }
  }
  return crc.inv()
}

private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private const val RGB = 3
private const val BIT_DEPTH: Byte = 8
private const val TRUE_COLOR: Byte = 2
private const val ZLIB_METHOD: Byte = 0x78
private const val ZLIB_FLAGS: Byte = 0x01
private const val MAX_STORED = 0xFFFF
private const val BYTE = 0xFF
private const val BITS = 8
private const val ADLER_MOD = 65_521
private const val ADLER_SHIFT = 16
private const val CRC_POLYNOMIAL = 0xEDB88320.toInt()
