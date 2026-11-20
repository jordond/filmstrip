package dev.jordond.filmstrip.test

// The byte helpers the animated picture writers share. They stand alone so core's tests, which compile this directory
// in, never reach the rest of the fixtures module.

internal fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

internal fun le24(value: Int): ByteArray =
  byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte())

internal fun le32(value: Int): ByteArray = le24(value) + byteArrayOf((value ushr 24).toByte())

internal fun be32(value: Int): ByteArray = le32(value).reversedArray()

/**
 * [parts] one after the other, copied once.
 */
internal fun concat(parts: List<ByteArray>): ByteArray {
  val out = ByteArray(parts.sumOf { it.size })
  var at = 0
  for (part in parts) {
    part.copyInto(out, at)
    at += part.size
  }
  return out
}

internal fun ascii(text: String): ByteArray = text.encodeToByteArray()

/**
 * A PNG chunk of [type] holding [data], with its length and CRC.
 */
internal fun pngChunk(
  type: String,
  data: ByteArray,
): ByteArray {
  val out = ByteArray(CHUNK_FRAMING + data.size)
  be32(data.size).copyInto(out)
  ascii(type).copyInto(out, CHUNK_LENGTH)
  data.copyInto(out, CHUNK_LENGTH + CHUNK_TYPE)
  val crc = crc32(out, from = CHUNK_LENGTH, to = CHUNK_LENGTH + CHUNK_TYPE + data.size)
  be32(crc).copyInto(out, CHUNK_LENGTH + CHUNK_TYPE + data.size)
  return out
}

/**
 * [bytes] as a zlib stream of stored deflate blocks.
 */
internal fun zlibStored(bytes: ByteArray): ByteArray {
  val parts = mutableListOf(byteArrayOf(0x78, 0x01))
  var from = 0
  do {
    val to = minOf(bytes.size, from + MAX_STORED_BLOCK)
    val length = to - from
    parts += byteArrayOf(if (to == bytes.size) 1 else 0) + le16(length) + le16(length.inv() and MAX_STORED_BLOCK)
    parts += bytes.copyOfRange(from, to)
    from = to
  } while (from < bytes.size)
  parts += be32(adler32(bytes))
  return concat(parts)
}

internal fun crc32(
  bytes: ByteArray,
  from: Int = 0,
  to: Int = bytes.size,
): Int {
  var crc = -1
  for (index in from until to) {
    crc = crc xor (bytes[index].toInt() and BYTE_MASK)
    repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor CRC_POLYNOMIAL else crc ushr 1 }
  }
  return crc.inv()
}

internal fun adler32(bytes: ByteArray): Int {
  var a = 1
  var b = 0
  for (byte in bytes) {
    a = (a + (byte.toInt() and BYTE_MASK)) % ADLER_MODULUS
    b = (b + a) % ADLER_MODULUS
  }
  return (b shl 16) or a
}

internal const val BYTE_MASK: Int = 0xFF

private const val MAX_STORED_BLOCK = 0xFFFF
private const val CHUNK_LENGTH = 4
private const val CHUNK_TYPE = 4
private const val CHUNK_FRAMING = CHUNK_LENGTH + CHUNK_TYPE + 4
private const val ADLER_MODULUS = 65521
private const val CRC_POLYNOMIAL = 0xEDB88320.toInt()
