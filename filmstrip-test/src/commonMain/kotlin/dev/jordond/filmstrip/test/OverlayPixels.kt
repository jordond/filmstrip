package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.roundToInt

/**
 * The overlay images the pixel pins draw, where a blended overlay pixel should land, and how far an
 * encoded reading of one may sit from it.
 *
 * An overlay test paints its image from [pngOf] so it knows the colour it authored, predicts a partly
 * opaque draw with [sourceOver], and compares an exported reading with [ENCODED_TOLERANCE]. The
 * authored colours themselves and the colour-handling tolerance live in [AuthoredColours].
 */
@InternalFilmstripApi
public object OverlayPixels {
  /**
   * How far a blended overlay pixel read back out of an H.264 export may sit from its prediction, in
   * code values per channel.
   *
   * The prediction is the same pixel read without the encode, or a blend of readings off the same
   * file. Either way each side has been through the encoder and decoder at most once, and this bounds
   * the codec noise that round trip leaves on a pixel mixed from the overlay and the frame under it. It
   * says nothing about colour handling, which [AuthoredColours.TOLERANCE] measures.
   */
  public const val ENCODED_TOLERANCE: Float = 6f

  /**
   * Where a source over draw of a channel at [over] with [opacity] leaves a channel at [under], worked
   * on the eight-bit codes themselves and rounded to the nearest code.
   */
  public fun sourceOver(
    under: Int,
    over: Int,
    opacity: Float,
  ): Int = (under + opacity * (over - under)).roundToInt()

  /**
   * An opaque PNG of [size] painted [argb] all over, as the bytes of the file.
   *
   * The image is eight-bit RGBA with no filtering, and its deflate stream holds the rows stored rather
   * than compressed, so a test builds its overlay image with no binary in the tree. The one stored
   * block caps it at 65535 bytes of rows.
   */
  public fun pngOf(
    size: Size,
    argb: Int,
  ): ByteArray {
    val stride = size.width * CHANNELS + 1
    val rows = ByteArray(stride * size.height)
    require(rows.size <= MAX_STORED_BLOCK) { "A ${size.width}x${size.height} PNG does not fit one stored block." }
    for (index in rows.indices) {
      rows[index] =
        when ((index % stride - 1) % CHANNELS) {
          -1 -> 0
          0 -> (argb shr RED_SHIFT).toByte()
          1 -> (argb shr GREEN_SHIFT).toByte()
          2 -> argb.toByte()
          else -> (argb ushr ALPHA_SHIFT).toByte()
        }
    }

    val header = int32(size.width) + int32(size.height) + byteArrayOf(8, 6, 0, 0, 0)
    val zlib =
      byteArrayOf(0x78, 0x01, 0x01) +
        int16LittleEndian(rows.size) +
        int16LittleEndian(rows.size.inv() and MAX_STORED_BLOCK) +
        rows +
        int32(adler32(rows))

    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
      chunk("IHDR", header) +
      chunk("IDAT", zlib) +
      chunk("IEND", ByteArray(0))
  }

  private fun chunk(
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
      crc = crc xor (byte.toInt() and BYTE)
      repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
    }
    return crc.inv()
  }

  private fun adler32(bytes: ByteArray): Int {
    var a = 1
    var b = 0
    for (byte in bytes) {
      a = (a + (byte.toInt() and BYTE)) % ADLER_MODULUS
      b = (b + a) % ADLER_MODULUS
    }
    return (b shl 16) or a
  }

  private const val CHANNELS = 4
  private const val RED_SHIFT = 16
  private const val GREEN_SHIFT = 8
  private const val ALPHA_SHIFT = 24
  private const val BYTE = 0xFF
  private const val ADLER_MODULUS = 65521
  private const val MAX_STORED_BLOCK = 0xFFFF
}
