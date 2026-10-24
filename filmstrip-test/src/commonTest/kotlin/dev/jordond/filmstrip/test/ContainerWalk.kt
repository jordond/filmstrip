package dev.jordond.filmstrip.test

/**
 * A PNG chunk as it sits in the file, its type, its data and the checksum stored after it.
 */
internal class PngChunk(
  val type: String,
  val data: ByteArray,
  val crc: Int,
)

/**
 * A box as it sits in a HEIF file, its type and where its payload starts and ends in the file.
 */
internal class HeifBox(
  val type: String,
  val start: Int,
  val end: Int,
)

/**
 * Walks the chunks of [png] after its signature, in file order.
 */
internal fun pngChunks(png: ByteArray): List<PngChunk> {
  val chunks = mutableListOf<PngChunk>()
  var offset = PNG_SIGNATURE_BYTES
  while (offset < png.size) {
    val length = png.int32At(offset)
    val data = png.copyOfRange(offset + 8, offset + 8 + length)
    chunks += PngChunk(png.textAt(offset + 4), data, png.int32At(offset + 8 + length))
    offset += length + PNG_CHUNK_OVERHEAD
  }
  return chunks
}

/**
 * The CRC-32 of [bytes], worked from a lookup table rather than bit by bit.
 */
internal fun tableCrc32(bytes: ByteArray): Int {
  var crc = -1
  for (byte in bytes) {
    crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
  }
  return crc.inv()
}

/**
 * The bytes a zlib stream of stored deflate blocks holds.
 */
internal fun storedInflate(zlib: ByteArray): ByteArray {
  var out = ByteArray(0)
  var offset = 2
  do {
    val last = zlib[offset].toInt() and 1 == 1
    val length = (zlib[offset + 1].toInt() and 0xFF) or (zlib[offset + 2].toInt() and 0xFF shl 8)
    out += zlib.copyOfRange(offset + 5, offset + 5 + length)
    offset += 5 + length
  } while (!last)
  return out
}

/**
 * Walks the boxes of [bytes] from [start] to [end], every box's size taken as a four byte size.
 */
internal fun heifBoxes(
  bytes: ByteArray,
  start: Int = 0,
  end: Int = bytes.size,
): List<HeifBox> {
  val boxes = mutableListOf<HeifBox>()
  var offset = start
  while (offset < end) {
    val size = bytes.int32At(offset)
    boxes += HeifBox(bytes.textAt(offset + 4), offset + 8, offset + size)
    offset += size
  }
  check(offset == end) { "The boxes run to $offset, past $end" }
  return boxes
}

/**
 * The boxes inside [box], skipping the four bytes of version and flags first when it is a full box.
 */
internal fun ByteArray.childrenOf(
  box: HeifBox,
  fullBox: Boolean = false,
): List<HeifBox> = heifBoxes(this, box.start + if (fullBox) 4 else 0, box.end)

internal fun List<HeifBox>.named(type: String): HeifBox = single { it.type == type }

internal fun ByteArray.textAt(offset: Int): String = decodeToString(offset, offset + 4)

internal fun ByteArray.uInt8At(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun ByteArray.uInt16At(offset: Int): Int = (uInt8At(offset) shl 8) or uInt8At(offset + 1)

internal fun ByteArray.int32At(offset: Int): Int = (uInt16At(offset) shl 16) or uInt16At(offset + 2)

private const val PNG_SIGNATURE_BYTES = 8

// A chunk's length, type and checksum around its data.
private const val PNG_CHUNK_OVERHEAD = 12

private val CRC_TABLE =
  IntArray(256) { index ->
    (0 until 8).fold(index) { crc, _ -> if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
  }
