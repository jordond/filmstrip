package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.PictureOrientation

/**
 * The stored size of a JPEG XL codestream from its `SizeHeader`, and the orientation its `ImageMetadata` gives.
 */
internal class JxlHeader(
  val width: Int,
  val height: Int,
  val orientation: PictureOrientation,
)

/**
 * Reads the [JxlHeader] at the start of [codestream], throwing [MalformedPicture] when it does not open with the
 * codestream signature or ends inside the fields read.
 *
 * Fields are read least significant bit first. The orientation sits behind the metadata's all_default and
 * extra_fields flags, and reads as [PictureOrientation.Normal] when either leaves it out.
 */
internal fun jxlHeaderOf(codestream: ByteView): JxlHeader {
  if (!codestream.matches(0, JXL_CODESTREAM)) malformed()
  val bits = JxlBitReader(codestream, JXL_CODESTREAM.size * 8L)
  val small = bits.read(1) == 1
  val height = bits.side(small)
  val ratio = bits.read(3)
  val width = if (ratio == 0) bits.side(small) else (height.toLong() * RATIOS[ratio] / RATIO_BASE[ratio]).toInt()
  val orientation =
    when {
      bits.read(1) == 1 -> PictureOrientation.Normal
      bits.read(1) == 0 -> PictureOrientation.Normal
      else -> PictureOrientation.ofExif(bits.read(3) + 1)
    }
  return JxlHeader(width, height, orientation)
}

private class JxlBitReader(
  private val view: ByteView,
  private var position: Long,
) {
  fun read(count: Int): Int {
    var value = 0
    for (bit in 0 until count) {
      val byte = view.u8((position ushr 3).toInt())
      value = value or (((byte ushr (position and 7).toInt()) and 1) shl bit)
      position++
    }
    return value
  }

  // A side in eighths of up to 256 when [small], or in full after a two bit selector of 9, 13, 18 or 30 bits.
  fun side(small: Boolean): Int = if (small) (read(5) + 1) * 8 else read(SIZE_BITS[read(2)]) + 1
}

private val SIZE_BITS = intArrayOf(9, 13, 18, 30)

// The width over height of each ratio a SizeHeader can name instead of stating its width, from 1 to 7.
private val RATIOS = longArrayOf(0, 1, 12, 4, 3, 16, 5, 2)
private val RATIO_BASE = longArrayOf(1, 1, 10, 3, 2, 9, 4, 1)
