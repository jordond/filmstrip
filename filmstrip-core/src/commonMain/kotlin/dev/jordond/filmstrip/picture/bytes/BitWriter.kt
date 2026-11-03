package dev.jordond.filmstrip.picture.bytes

// Writes bits most significant first into a growing array.
internal class BitWriter(
  capacity: Int,
) {
  private var bytes = ByteArray(capacity)
  private var position = 0L

  fun write(
    count: Int,
    value: Int,
  ) {
    for (bit in count - 1 downTo 0) put(value ushr bit and 1)
  }

  // Copies the bits of [source] from [start] up to [end].
  fun copy(
    source: ByteArray,
    start: Long,
    end: Long,
  ) {
    for (at in start until end) {
      val index = at.toInt()
      put(source[index ushr 3].toInt() ushr (7 - (index and 7)) and 1)
    }
  }

  // The bits written so far, padded with zeros to the end of their last byte.
  fun aligned(): ByteArray = bytes.copyOf(((position + 7) / 8).toInt())

  private fun put(bit: Int) {
    val index = (position ushr 3).toInt()
    if (index == bytes.size) bytes = bytes.copyOf(bytes.size * 2 + 1)
    if (bit == 1) bytes[index] = (bytes[index].toInt() or (0x80 ushr (position and 7).toInt())).toByte()
    position++
  }
}
