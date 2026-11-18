package dev.jordond.filmstrip.effects.overlay

import platform.CoreImage.CIImage
import kotlin.time.TimeSource

internal fun avis(): ByteArray = byteArrayOf(0, 0, 0, 16) + "ftypavis".encodeToByteArray() + ByteArray(4)

// The file with the Display P3 primaries and a 2.2 gamma written ahead of its first frame, so it reads as tagged.
internal fun withDisplayP3(png: ByteArray): ByteArray {
  val gamma = chunk("gAMA", be32(GAMMA))
  val primaries =
    chunk(
      "cHRM",
      listOf(WHITE_X, WHITE_Y, RED_X, RED_Y, GREEN_X, GREEN_Y, BLUE_X, BLUE_Y)
        .flatMap {
          be32(it).toList()
        }.toByteArray(),
    )
  val afterHeader = PNG_SIGNATURE_BYTES + IHDR_CHUNK_BYTES
  return png.copyOfRange(0, afterHeader) + gamma + primaries + png.copyOfRange(afterHeader, png.size)
}

private fun chunk(
  type: String,
  data: ByteArray,
): ByteArray {
  val body = type.encodeToByteArray() + data
  return be32(data.size) + body + be32(crc32(body))
}

private fun be32(value: Int): ByteArray = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

private fun crc32(bytes: ByteArray): Int {
  var crc = -1
  for (byte in bytes) {
    crc = crc xor (byte.toInt() and 0xFF)
    repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor CRC_POLYNOMIAL else crc ushr 1 }
  }
  return crc.inv()
}

private const val PNG_SIGNATURE_BYTES = 8
private const val IHDR_CHUNK_BYTES = 25
private const val CRC_POLYNOMIAL = 0xEDB88320.toInt()
private const val GAMMA = 45455
private const val WHITE_X = 31270
private const val WHITE_Y = 32900
private const val RED_X = 68000
private const val RED_Y = 32000
private const val GREEN_X = 26500
private const val GREEN_Y = 69000
private const val BLUE_X = 15000
private const val BLUE_Y = 6000

// The median milliseconds one float image takes to make, from codes whose floats were let go before each run.
internal fun median(
  pixels: OverlayPixels,
  convert: (OverlayPixels) -> CIImage,
): Long {
  val times =
    List(TIMED_RUNS) {
      pixels.dropFloats()
      val start = TimeSource.Monotonic.markNow()
      convert(pixels)
      (TimeSource.Monotonic.markNow() - start).inWholeMilliseconds
    }
  return times.sorted()[TIMED_RUNS / 2]
}

private const val TIMED_RUNS = 7
