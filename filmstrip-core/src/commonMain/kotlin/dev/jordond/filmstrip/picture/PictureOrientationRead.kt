package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.heif.heifOrientation
import dev.jordond.filmstrip.picture.png.PNG_IEND
import dev.jordond.filmstrip.picture.png.PNG_SIGNATURE

/**
 * The orientation an encoded picture says it is shown in, read straight out of its container without decoding it.
 *
 * - JPEG: tag 274 in the first image directory of the first `APP1` Exif segment ahead of the first scan.
 * - PNG: tag 274 in the `eXIf` chunk whose CRC holds.
 * - WebP: tag 274 in the `EXIF` chunk of an extended file whose `VP8X` header flags one.
 * - TIFF and BigTIFF: tag 274 of the first image directory.
 * - AVIF and HEIC: every `irot` turn and `imir` mirror on the primary item, in the order its associations list them.
 * - JPEG XL: the orientation in the codestream's image metadata, from a bare codestream or from the `jxlc` box or the
 * `jxlp` boxes of a boxed file.
 *
 * The container is walked through a [PictureWalk], the same walk the ranged read takes, so its reads stay inside
 * [bytes] and inside the walk's budget. A JPEG segment or JPEG XL box that runs past the end of [bytes] is read as far
 * as it goes. Any other format, a missing or unreadable tag, a tag that is not a SHORT, a value outside 1 to 8, a
 * truncated or malformed container and one that needs more than the budget all give [PictureOrientation.Normal].
 */
@InternalFilmstripApi
public fun pictureOrientationOf(bytes: ByteArray): PictureOrientation =
  completeInPlace { pictureOrientationOf(ByteArrayPicture(bytes)) }

/**
 * The orientation a file of [size] bytes says it is shown in, fetching only the ranges of it that [read] has to.
 *
 * Takes the walk [pictureOrientationOf] takes over bytes in memory, so a file answers the same either way. Only the
 * container's headers and the block holding the tag are fetched, wherever in the file the container puts them, and
 * nothing outside `0..size` is asked for.
 */
internal suspend fun pictureOrientationOf(
  size: Long,
  read: ByteRangeRead,
): PictureOrientation = pictureOrientationOf(RangedPicture(size, read))

private suspend fun pictureOrientationOf(picture: PictureBytes): PictureOrientation =
  walkPicture(picture) { orientation() } ?: PictureOrientation.Normal

/**
 * These bytes with the Exif orientation tag [pictureOrientationOf] reads written as 1, so a decoder that turns a
 * picture by that tag hands it back as stored. The tag keeps its place and its byte order, and a PNG's `eXIf` chunk
 * gets its CRC written again, so every other byte stays as it was.
 *
 * Only a JPEG, PNG, WebP or TIFF tag is written. [bytes] come back as they are when there is no tag to write, when it
 * is 1 already, or for any other container, an AVIF or HEIC's `irot` and `imir` and a JPEG XL's codestream orientation
 * included.
 */
@InternalFilmstripApi
public fun pictureWithoutOrientationTag(bytes: ByteArray): ByteArray {
  val tag = completeInPlace { walkPicture(ByteArrayPicture(bytes)) { orientationTag() } } ?: return bytes
  if (tag.value == PictureOrientation.Normal.exif) return bytes
  val written = bytes.copyOf()
  val at = tag.valueAt.toInt()
  written[at] = if (tag.little) 1 else 0
  written[at + 1] = if (tag.little) 0 else 1
  val chunk = tag.pngChunk ?: return written
  val crc = ByteView(written, written.size).crc32(chunk.first.toInt(), chunk.last.toInt() + 1)
  val crcAt = chunk.last.toInt() + 1
  for (index in 0 until PNG_CRC.toInt()) written[crcAt + index] = (crc ushr (BYTE_BITS * (3 - index))).toByte()
  return written
}

// Where the SHORT orientation tag sits: its value, its byte order, what it holds and the type and data of the PNG
// chunk whose CRC covers it.
private class OrientationTag(
  val valueAt: Long,
  val little: Boolean,
  val value: Int,
  val pngChunk: LongRange? = null,
)

private suspend fun PictureWalk.orientation(): PictureOrientation =
  when (val container = container()) {
    PictureContainer.JxlCodestream, PictureContainer.JxlBoxed -> jxlHeaderOf(jxlCodestreamHead(container)).orientation
    PictureContainer.Heif -> heifMeta()?.let(::heifOrientation) ?: PictureOrientation.Normal
    null -> PictureOrientation.Normal
    else -> PictureOrientation.ofExif(exifTag(container)?.value ?: PictureOrientation.Normal.exif)
  }

private suspend fun PictureWalk.orientationTag(): OrientationTag? = container()?.let { exifTag(it) }

private suspend fun PictureWalk.exifTag(container: PictureContainer): OrientationTag? =
  when (container) {
    PictureContainer.Jpeg -> jpegTag()
    PictureContainer.Png -> pngTag()
    PictureContainer.Webp -> webpTag()
    PictureContainer.Tiff -> tiffTag(0, size)
    else -> null
  }

private suspend fun PictureWalk.jpegTag(): OrientationTag? {
  forEachJpegSegment { marker, dataStart, end ->
    if (marker == APP1 && matches(dataStart, EXIF_HEADER, end)) return tiffTag(dataStart + EXIF_HEADER.size, end)
  }
  return null
}

// The chunk is read whole only when it is an eXIf, to check its CRC, and its TIFF block is then read out of that.
private suspend fun PictureWalk.pngTag(): OrientationTag? {
  var pos = PNG_SIGNATURE.size.toLong()
  while (true) {
    val length = u32(pos)
    val type = u32(pos + 4).toInt()
    if (length > size - pos - PNG_CHUNK_OVERHEAD) malformed()
    if (type == PNG_IEND) return null
    val end = pos + PNG_CHUNK_HEADER + length
    if (type == EXIF_PNG) {
      val chunk = block(pos + 4, length + PNG_CHUNK_HEADER)
      val crcAt = 4 + length.toInt()
      if (chunk.u32(crcAt) == chunk.crc32(0, crcAt)) {
        val tag = exifTag(pos + PNG_CHUNK_HEADER, end) ?: return null
        return OrientationTag(tag.valueAt, tag.little, tag.value, pos + 4 until end)
      }
    }
    pos = end + PNG_CRC
  }
}

// Every chunk read stays inside the RIFF, starting from the VP8X that has to flag the EXIF chunk.
private suspend fun PictureWalk.webpTag(): OrientationTag? {
  val riff = riffEnd()
  var pos = WEBP_FIRST_CHUNK
  if (u32(pos, limit = riff).toInt() != WEBP_VP8X || u8(pos + RIFF_CHUNK_HEADER, riff) and VP8X_EXIF_FLAG == 0) {
    return null
  }
  while (true) {
    val length = u32(pos + 4, little = true, limit = riff)
    val end = pos + RIFF_CHUNK_HEADER + length
    if (end > riff) malformed()
    if (u32(pos, limit = riff).toInt() == EXIF_WEBP) return exifTag(pos + RIFF_CHUNK_HEADER, end)
    pos = end + (length and 1L)
  }
}

// An eXIf or EXIF payload is a TIFF block, though some writers lead it with the identifier a JPEG segment carries.
private suspend fun PictureWalk.exifTag(
  start: Long,
  end: Long,
): OrientationTag? = tiffTag(if (matches(start, EXIF_HEADER, end)) start + EXIF_HEADER.size else start, end)

// Tag 274 in the first image directory of the TIFF block from [base] to [end], or null when it holds none. A tag of
// any type but SHORT is none, so it reads as 1.
private suspend fun PictureWalk.tiffTag(
  base: Long,
  end: Long,
): OrientationTag? {
  val directory = tiffDirectory(base, end)
  forEachTiffEntry(directory) { tag, entry ->
    if (tag == ORIENTATION_TAG) {
      if (u16(entry + 2, directory.little, end) != TIFF_SHORT) return null
      val valueAt = directory.valueAt(entry)
      return OrientationTag(valueAt, directory.little, u16(valueAt, directory.little, end))
    }
  }
  return null
}

private val EXIF_HEADER = ascii("Exif\u0000\u0000")
private val EXIF_PNG = fourcc("eXIf")
private val EXIF_WEBP = fourcc("EXIF")

private const val APP1 = 0xE1
private const val PNG_CHUNK_HEADER = 8L
private const val PNG_CRC = 4L
private const val PNG_CHUNK_OVERHEAD = 12L
private const val VP8X_EXIF_FLAG = 0x08
private const val ORIENTATION_TAG = 274
private const val TIFF_SHORT = 3
private const val BYTE_BITS = 8
