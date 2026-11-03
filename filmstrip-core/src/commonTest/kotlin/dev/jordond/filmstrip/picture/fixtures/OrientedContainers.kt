package dev.jordond.filmstrip.picture.fixtures

import dev.jordond.filmstrip.picture.bytes.ascii

// Pictures tagged with an orientation in each container pictureOrientationOf reads, written field by field so the
// tag a test asks for is a number in the test.

/**
 * A classic TIFF block whose first directory holds a width and then the orientation tag of [type] set to [value].
 */
internal fun exifBlock(
  value: Int,
  little: Boolean = false,
  type: Int = TIFF_SHORT_TYPE,
  big: Boolean = false,
): ByteArray = tiffWith(null, Triple(256, 3, 64), Triple(EXIF_ORIENTATION_TAG, type, value), little = little, big = big)

/**
 * Where the orientation value of [exifBlock] ends, which is as much of a classic block as a read has to see.
 */
internal const val EXIF_VALUE_END = 8 + 2 + 2 * 12 - 2

/**
 * A one pixel PNG carrying [block] in an `eXIf` chunk, ahead of its `IDAT` unless [afterImageData] is set.
 */
internal fun pngWithExif(
  block: ByteArray,
  afterImageData: Boolean = false,
): ByteArray {
  val chunk = bytes { pngChunk("eXIf", block) }
  return if (afterImageData) png(afterImageData = chunk) else png(chunk)
}

/**
 * Where the `eXIf` chunk of a [pngWithExif] written ahead of its `IDAT` starts, past the signature and `IHDR`.
 */
internal const val PNG_EXIF_AT = 8 + 25

/**
 * An extended WebP whose `VP8X` flags an Exif chunk when [flagged] is set, with an odd sized image chunk and then
 * [block] in its `EXIF` chunk.
 */
internal fun webpWithExif(
  block: ByteArray,
  flagged: Boolean = true,
  riffSize: Long? = null,
): ByteArray {
  val chunks =
    bytes {
      webpChunk("VP8X", ByteArray(10).also { if (flagged) it[0] = 0x08 })
      webpChunk("VP8L", ByteArray(5))
      webpChunk("EXIF", block)
    }
  return bytes {
    ascii("RIFF")
    u32(riffSize ?: (4L + chunks.size), little = true)
    ascii("WEBP")
    raw(chunks)
  }
}

/**
 * A HEIF whose primary item turns [turns] quarter turns in an `irot` and mirrors on [axis] in an `imir`, leaving
 * either property out when it is null.
 *
 * The item lists the `irot` first unless [mirrorFirst] is true. An AVIF unless [fileType] says otherwise.
 */
internal fun heifOriented(
  turns: Int?,
  axis: Int?,
  mirrorFirst: Boolean = false,
  fileType: ByteArray = ftyp("avif", "mif1"),
): ByteArray {
  val ispe = fullBox("ispe", 0, 0, ByteArray(8))
  val irot = turns?.let { box("irot", byteArrayOf(it.toByte())) }
  val imir = axis?.let { box("imir", byteArrayOf(it.toByte())) }
  val transforms = if (mirrorFirst) listOf(imir, irot) else listOf(irot, imir)
  return heifItems(listOf(ispe) + transforms.filterNotNull(), fileType = fileType)
}

/**
 * A JPEG XL codestream with the given orientation in its image metadata, or with none when [orientation] is null.
 *
 * [small] writes the SizeHeader's sides in eighths and a ratio, and otherwise writes both sides in full with the 13
 * and 18 bit selectors. [allDefault] marks the metadata as all default.
 */
internal fun jxlCodestream(
  orientation: Int?,
  small: Boolean = false,
  allDefault: Boolean = false,
): ByteArray {
  val bits = JxlBits()
  bits.write(if (small) 1 else 0, 1)
  if (small) {
    bits.write(4, 5)
    bits.write(1, 3)
  } else {
    bits.write(1, 2).write(1000, 13)
    bits.write(0, 3)
    bits.write(2, 2).write(70_000, 18)
  }
  bits.write(if (allDefault) 1 else 0, 1)
  if (!allDefault) {
    bits.write(if (orientation != null) 1 else 0, 1)
    orientation?.let { bits.write(it - 1, 3) }
    // have_intr_size, have_preview and have_animation, then some of the bit depth.
    bits.write(0b1011, 4)
  }
  return byteArrayOf(0xFF.toByte(), 0x0A) + bits.toByteArray() + ByteArray(8) { 0x5A }
}

/**
 * [codestream] boxed in a JPEG XL container, whole in one `jxlc` box, or split into `jxlp` boxes at [splitAt] when
 * that is set.
 */
internal fun jxlBoxed(
  codestream: ByteArray,
  splitAt: Int? = null,
): ByteArray {
  val boxes =
    if (splitAt == null) {
      box("jxlc", codestream)
    } else {
      box("jxlp", bytes { u32(0) }, codestream.copyOfRange(0, splitAt)) +
        box("jxll", byteArrayOf(5)) +
        box("jxlp", bytes { u32(0x80000001L) }, codestream.copyOfRange(splitAt, codestream.size))
    }
  return jxlOpening() + boxes
}

/**
 * The signature box and file type box every boxed JPEG XL opens with, ahead of the boxes holding its codestream.
 */
internal fun jxlOpening(): ByteArray =
  byteArrayOf(0, 0, 0, 0x0C) + asciiBytes("JXL ") + byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A) + ftyp("jxl ", "jxl ")

internal class JxlBits {
  private val bits = mutableListOf<Int>()

  fun write(
    value: Int,
    count: Int,
  ): JxlBits = apply { for (bit in 0 until count) bits += (value ushr bit) and 1 }

  fun toByteArray(): ByteArray =
    ByteArray((bits.size + 7) / 8) { index ->
      (0 until 8).sumOf { bit -> (bits.getOrElse(index * 8 + bit) { 0 }) shl bit }.toByte()
    }
}

internal const val EXIF_ORIENTATION_TAG = 274
internal const val TIFF_SHORT_TYPE = 3
