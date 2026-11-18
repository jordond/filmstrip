package dev.jordond.filmstrip.picture.bytes

/**
 * A bounds checked view of [bytes] up to [end], read the way picture containers lay their fields out.
 *
 * Every read past [end] throws [MalformedPicture], so a container reader can walk untrusted bytes and catch one
 * exception for the whole walk.
 */
internal class ByteView(
  val bytes: ByteArray,
  val end: Int,
) {
  fun within(limit: Int): ByteView = ByteView(bytes, minOf(limit, end))

  fun matches(
    at: Int,
    expected: ByteArray,
  ): Boolean = at >= 0 && end - at >= expected.size && expected.indices.all { bytes[at + it] == expected[it] }

  fun u8(at: Int): Int {
    if (at !in 0 until end) malformed()
    return bytes[at].toInt() and 0xFF
  }

  fun u16(
    at: Int,
    little: Boolean = false,
  ): Int = if (little) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)

  fun u32(
    at: Int,
    little: Boolean = false,
  ): Long {
    val first = u16(at, little).toLong()
    val second = u16(at + 2, little).toLong()
    return if (little) first or (second shl 16) else (first shl 16) or second
  }

  fun u64(
    at: Int,
    little: Boolean = false,
  ): Long {
    val first = u32(at, little)
    val second = u32(at + 4, little)
    val (high, low) = if (little) second to first else first to second
    if (high > Int.MAX_VALUE) malformed()
    return (high shl 32) or low
  }

  fun span(
    start: Int,
    length: Long,
  ): Int {
    if (start !in 0..end || length !in 0..(end - start).toLong()) malformed()
    return start + length.toInt()
  }

  fun offset(value: Long): Int = span(0, value)

  fun crc32(
    start: Int,
    stop: Int,
  ): Long {
    span(start, (stop - start).toLong())
    val data = bytes
    val table = CRC_TABLE
    var crc = -1
    var at = start
    while (at + 4 <= stop) {
      crc = crc xor
        (
          (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
        )
      crc = table[768 + (crc and 0xFF)] xor table[512 + ((crc ushr 8) and 0xFF)] xor
        table[256 + ((crc ushr 16) and 0xFF)] xor table[crc ushr 24]
      at += 4
    }
    while (at < stop) crc = table[(crc xor data[at++].toInt()) and 0xFF] xor (crc ushr 8)
    return crc.inv().toLong() and 0xFFFFFFFFL
  }

  fun copyInto(
    destination: ByteArray,
    at: Int,
    start: Int,
    stop: Int,
  ): Int {
    span(start, (stop - start).toLong())
    bytes.copyInto(destination, at, start, stop)
    return at + stop - start
  }

  fun profile(
    start: Int,
    stop: Int,
  ): ByteArray {
    span(start, (stop - start).toLong())
    return bytes.copyOfRange(start, start + profileSize((stop - start).toLong()))
  }
}

internal class MalformedPicture : Exception()

internal fun malformed(): Nothing = throw MalformedPicture()

/**
 * Runs [read] over a piece of a container that is not understood when malformed, so a bad piece gives null and
 * defers to the next description instead of failing the whole container.
 */
internal inline fun <T : Any> deferring(read: () -> T?): T? =
  try {
    read()
  } catch (_: MalformedPicture) {
    null
  }

internal fun profileSize(size: Long): Int = if (size in 0..MAX_PROFILE_SIZE) size.toInt() else malformed()

internal fun fourcc(name: String): Int = name.fold(0) { code, char -> (code shl 8) or char.code }

internal fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

// Four 256 entry tables back to back, the first the plain CRC-32 table and each next one the effect of a further zero
// byte, so four bytes are folded in at a time.
private val CRC_TABLE =
  IntArray(1024).also { table ->
    for (index in 0 until 256) {
      table[index] =
        (0 until 8).fold(index) { crc, _ -> if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
    }
    for (index in 0 until 256) {
      for (slice in 1 until 4) {
        val previous = table[(slice - 1) * 256 + index]
        table[slice * 256 + index] = table[previous and 0xFF] xor (previous ushr 8)
      }
    }
  }

internal const val MAX_PROFILE_SIZE = 16 * 1024 * 1024
