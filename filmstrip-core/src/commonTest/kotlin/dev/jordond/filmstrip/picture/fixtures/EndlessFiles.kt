package dev.jordond.filmstrip.picture.fixtures

import dev.jordond.filmstrip.picture.bytes.ascii

// Files far larger than a walk's budget, laid out as an opening and then one small step over and over, read through
// ranged reads without ever being held whole.

/**
 * A file of [opening] and then [step] over and over, handing back the [count] bytes at any start a ranged read asks
 * for.
 *
 * The steps are copied out of one run of them at least 4 KiB long, so each read costs a few array copies rather than
 * a lookup per byte.
 */
internal fun repeating(
  opening: ByteArray,
  step: ByteArray,
): (Long, Int) -> ByteArray {
  val period = ByteArray(step.size * ((MIN_PERIOD + step.size - 1) / step.size)) { step[it % step.size] }
  return { start, count ->
    val bytes = ByteArray(count)
    var filled = 0
    if (start < opening.size) {
      filled = minOf(count.toLong(), opening.size - start).toInt()
      opening.copyInto(bytes, 0, start.toInt(), start.toInt() + filled)
    }
    if (filled < count) {
      var at = ((start + filled - opening.size) % period.size).toInt()
      while (filled < count) {
        val chunk = minOf(count - filled, period.size - at)
        period.copyInto(bytes, filled, at, at + chunk)
        filled += chunk
        at = 0
      }
    }
    bytes
  }
}

/**
 * The header of a little endian BigTIFF whose first directory, straight after it, says it holds [count] entries.
 */
internal fun bigTiffHeader(count: Long): ByteArray =
  bytes {
    ascii("II").u16(43, little = true).u16(8, little = true).u16(0, little = true)
    u64(16, little = true)
    u64(count, little = true)
  }

private const val MIN_PERIOD = 4096
