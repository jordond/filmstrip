package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes

// Pictures whose orientation tag starts further into the file than PAST_THE_HEAD, each placed where its own container
// lets it sit, so a read that only looked at the head of a file would find no tag in any of them.

/**
 * How far into each late tagged picture its tag starts at the least, past the 64 KiB a camera's own Exif segment can
 * fill.
 */
internal const val PAST_THE_HEAD: Int = 64 * 1024 + 4096

/**
 * The stored bounds of [lateTaggedPng], wide and not square so a swapped size cannot pass by coincidence.
 */
internal val LATE_PNG_SIZE: Size = Size(160, 150)

/**
 * The stored bounds of [lateTaggedTiff].
 */
internal val LATE_TIFF_SIZE: Size = Size(300, 240)

/**
 * A decodable RGB PNG of [LATE_PNG_SIZE] whose `eXIf` chunk tagged [orientation] comes after all of its image data.
 */
internal fun lateTaggedPng(orientation: Int): ByteArray {
  val width = LATE_PNG_SIZE.width
  val height = LATE_PNG_SIZE.height
  val samples = IntArray(width * height * 3) { (it * 7) and 0xFF }
  val png = encodePng(width, height, colourType = 2, depth = 8, samples = samples, imageDataChunk = 8192)
  val end = png.size - PNG_IEND_CHUNK
  return png.copyOf(end) + bytes { pngChunk("eXIf", exifBlock(orientation)) } + png.copyOfRange(end, png.size)
}

/**
 * The still probe's JPEG tagged [orientation], with two full APP4 segments between its start and its Exif segment.
 */
internal fun lateTaggedJpeg(orientation: Int): ByteArray {
  val tagged = rotatedImageProbeBytes(orientation)
  val filler = bytes { repeat(2) { u8(0xFF).u8(0xE4).u16(0xFFFF).raw(ByteArray(0xFFFF - 2)) } }
  return tagged.copyOf(2) + filler + tagged.copyOfRange(2, tagged.size)
}

/**
 * A decodable 8 bit grey classic TIFF of [LATE_TIFF_SIZE] whose one strip comes first and whose first image directory,
 * holding [orientation], comes after it.
 */
internal fun lateTaggedTiff(
  orientation: Int,
  little: Boolean = true,
): ByteArray {
  val width = LATE_TIFF_SIZE.width
  val height = LATE_TIFF_SIZE.height
  val strip = width * height
  val short = { tag: Int, value: Int -> bytes { tiffEntry(tag, 3, 1, little).u16(value, little).u16(0, little) } }
  val long = { tag: Int, value: Long -> bytes { tiffEntry(tag, 4, 1, little).u32(value, little) } }
  val entries =
    listOf(
      short(256, width),
      short(257, height),
      short(258, 8),
      short(259, 1),
      short(262, 1),
      long(273, 8),
      short(EXIF_ORIENTATION_TAG, orientation),
      short(277, 1),
      short(278, height),
      long(279, strip.toLong()),
    )
  return bytes {
    ascii(if (little) "II" else "MM")
    u16(42, little)
    u32(8L + strip, little)
    raw(ByteArray(strip) { (it % 251).toByte() })
    u16(entries.size, little)
    entries.forEach { raw(it) }
    u32(0, little)
  }
}

/**
 * The stored bounds of [lateTaggedWebp].
 */
internal val LATE_WEBP_SIZE: Size = Size(120, 90)

/**
 * A decodable extended WebP of [LATE_WEBP_SIZE] flagging an Exif chunk, whose lossless image is followed by a padding
 * chunk that runs past [PAST_THE_HEAD] and then by its `EXIF` chunk tagged [orientation].
 *
 * The image is one transparent colour, held in a one entry palette whose every prefix code has a single symbol, so
 * each pixel takes no bits and any width and height decode from the same few bytes.
 */
internal fun lateTaggedWebp(orientation: Int): ByteArray {
  val width = LATE_WEBP_SIZE.width - 1L
  val height = LATE_WEBP_SIZE.height - 1L
  val canvas = bytes { u32(width, little = true).u32(height, little = true) }
  val chunks =
    bytes {
      webpChunk("VP8X", byteArrayOf(0x18, 0, 0, 0) + canvas.copyOf(3) + canvas.copyOfRange(4, 7))
      webpChunk("VP8L", bytes { u8(0x2F).u32(width or (height shl 14) or (1L shl 28), little = true) } + ONE_COLOUR)
      webpChunk("PAD ", ByteArray(PAST_THE_HEAD))
      webpChunk("EXIF", exifBlock(orientation))
    }
  return bytes {
    ascii("RIFF")
    u32(4L + chunks.size, little = true)
    ascii("WEBP")
    raw(chunks)
  }
}

/**
 * A [heifOriented] with a `free` box between its file type box and its `meta` box that pushes the `meta` past
 * [PAST_THE_HEAD].
 */
internal fun lateTaggedHeif(
  turns: Int?,
  axis: Int?,
): ByteArray {
  val heif = heifOriented(turns, axis)
  val fileType = ByteView(heif, heif.size).u32(0).toInt()
  return heif.copyOf(fileType) + box("free", ByteArray(PAST_THE_HEAD)) + heif.copyOfRange(fileType, heif.size)
}

/**
 * A [jxlBoxed] codestream tagged [orientation] with an `xml ` box ahead of its codestream boxes that pushes them past
 * [PAST_THE_HEAD].
 */
internal fun lateTaggedJxl(
  orientation: Int,
  splitAt: Int? = null,
): ByteArray {
  val boxed = jxlBoxed(jxlCodestream(orientation), splitAt)
  val view = ByteView(boxed, boxed.size)
  val codestream = JXL_SIGNATURE_SIZE + view.u32(JXL_SIGNATURE_SIZE).toInt()
  return boxed.copyOf(codestream) + box("xml ", ByteArray(PAST_THE_HEAD)) + boxed.copyOfRange(codestream, boxed.size)
}

// A lossless image's palette transform of one transparent entry, then its five single symbol prefix codes.
private val ONE_COLOUR = byteArrayOf(0x07, 0x10, 0x11, 0x11, 0x88.toByte(), 0x88.toByte(), 0xFE.toByte(), 0x07)

private const val PNG_IEND_CHUNK = 12
private const val JXL_SIGNATURE_SIZE = 12
