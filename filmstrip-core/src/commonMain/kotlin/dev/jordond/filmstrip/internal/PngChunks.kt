package dev.jordond.filmstrip.internal

/**
 * A PNG chunk of [type] whose data runs from [start] to [end], followed by its four byte CRC.
 */
internal class PngChunk(
  val type: Int,
  val start: Int,
  val end: Int,
) {
  val size: Int get() = end - start

  val next: Int get() = end + PNG_CRC_SIZE

  /**
   * Whether the chunk is critical, so a reader that does not know its type cannot skip it.
   */
  val isCritical: Boolean get() = (type ushr 24) and 0x20 == 0
}

/**
 * Reads the chunk whose length field sits at [pos], throwing [MalformedPicture] when its data or CRC runs past the end.
 */
internal fun ByteView.pngChunk(pos: Int): PngChunk {
  val type = u32(pos + 4).toInt()
  val end = span(pos + 8, u32(pos))
  span(end, PNG_CRC_SIZE.toLong())
  return PngChunk(type, pos + 8, end)
}

internal fun ByteView.crcHolds(chunk: PngChunk): Boolean = u32(chunk.end) == crc32(chunk.start - 4, chunk.end)

internal val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal val PNG_IHDR = fourcc("IHDR")
internal val PNG_PLTE = fourcc("PLTE")
internal val PNG_TRNS = fourcc("tRNS")
internal val PNG_IDAT = fourcc("IDAT")
internal val PNG_IEND = fourcc("IEND")

internal const val PNG_IHDR_SIZE = 13

private const val PNG_CRC_SIZE = 4
