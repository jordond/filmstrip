package dev.jordond.filmstrip.picture.animated

/**
 * Decodes the LZW stream of a GIF image into palette indices with whole number arithmetic only.
 *
 * The four tables are kept between calls, so decoding every frame of a file allocates them once.
 */
internal class GifLzw {
  private val prefix = IntArray(TABLE_SIZE)
  private val suffix = ByteArray(TABLE_SIZE)
  private val first = ByteArray(TABLE_SIZE)
  private val length = IntArray(TABLE_SIZE)

  /**
   * Reads codes from the first [size] bytes of [data] until [out] is full, and returns whether it was.
   *
   * A stream that ends or hits its end code before [out] is full, or holds a code that is not in the table, returns
   * false. Codes past what [out] holds are dropped.
   */
  fun decode(
    data: ByteArray,
    size: Int,
    minimumCodeSize: Int,
    out: ByteArray,
  ): Boolean {
    val clear = 1 shl minimumCodeSize
    val end = clear + 1
    for (code in 0 until clear) {
      prefix[code] = -1
      suffix[code] = code.toByte()
      first[code] = code.toByte()
      length[code] = 1
    }
    var width = minimumCodeSize + 1
    var next = end + 1
    var previous = -1
    var buffer = 0
    var held = 0
    var at = 0
    var written = 0
    while (written < out.size) {
      while (held < width) {
        if (at >= size) return false
        buffer = buffer or ((data[at++].toInt() and BYTE) shl held)
        held += BITS
      }
      val code = buffer and ((1 shl width) - 1)
      buffer = buffer ushr width
      held -= width
      if (code == clear) {
        width = minimumCodeSize + 1
        next = end + 1
        previous = -1
        continue
      }
      if (code == end) return false
      if (previous == -1) {
        if (code >= clear) return false
        out[written++] = code.toByte()
        previous = code
        continue
      }
      if (code > next || (code == next && next == TABLE_SIZE)) return false
      if (next < TABLE_SIZE) {
        prefix[next] = previous
        suffix[next] = if (code < next) first[code] else first[previous]
        first[next] = first[previous]
        length[next] = length[previous] + 1
        next++
      }
      val count = length[code]
      var index = written + count - 1
      var walk = code
      if (index < out.size) {
        while (walk != -1) {
          out[index--] = suffix[walk]
          walk = prefix[walk]
        }
      } else {
        while (walk != -1) {
          if (index < out.size) out[index] = suffix[walk]
          index--
          walk = prefix[walk]
        }
      }
      written += count
      previous = code
      if (next == (1 shl width) && width < MAX_WIDTH) width++
    }
    return true
  }

  private companion object {
    const val TABLE_SIZE = 4096
    const val MAX_WIDTH = 12
    const val BITS = 8
    const val BYTE = 0xFF
  }
}
