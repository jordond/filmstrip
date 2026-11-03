package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.heif.heifCrop
import dev.jordond.filmstrip.picture.heif.heifExtent
import dev.jordond.filmstrip.picture.png.PngExtent
import dev.jordond.filmstrip.picture.png.pngExtent

/**
 * The stored size of an encoded picture, ahead of any orientation it is shown in, read out of its container without
 * decoding it.
 *
 * - JPEG: the first start of frame segment ahead of the first scan.
 * - PNG: the `IHDR`, read through [pngExtent].
 * - WebP: the canvas of a `VP8X`, or the frame header of a simple `VP8` or `VP8L` file.
 * - TIFF and BigTIFF: the width and length tags of the first image directory.
 * - AVIF and HEIC: the last `ispe` in the primary item's first `ipma` entry of the first `iprp`, each `clap` cropping
 * the frame left ahead of it, then turned back to the stored picture, read through [heifExtent].
 * - JPEG XL: the codestream's `SizeHeader`.
 *
 * Walks [bytes] the way [pictureOrientationOf] does, inside the same budget. Gives null for any other format, a
 * missing or zero size and a truncated or malformed container.
 */
internal fun pictureExtentOf(bytes: ByteArray): Size? = completeInPlace { pictureExtentOf(ByteArrayPicture(bytes)) }

/**
 * The stored size of a file of [size] bytes that [read] fetches ranges of, the same answer [pictureExtentOf] gives
 * for the whole file in memory. Only the container's headers are fetched, wherever in the file they sit.
 */
internal suspend fun pictureExtentOf(
  size: Long,
  read: ByteRangeRead,
): Size? = pictureExtentOf(RangedPicture(size, read))

/**
 * The texels of an AVIF or HEIC's stored picture its `clap` properties keep, as [heifCrop] reads them, so a backend
 * whose decoder does not crop by a `clap` crops the picture the way libheif does.
 *
 * Null when nothing crops it, for any other container and wherever else [heifCrop] is null, so a backend cuts nothing
 * then and keeps whatever its decoder hands back.
 */
@InternalFilmstripApi
public fun pictureCropOf(bytes: ByteArray): PictureCrop? =
  completeInPlace {
    walkPicture(ByteArrayPicture(bytes)) {
      if (container() == PictureContainer.Heif) heifMeta()?.let(::heifCrop) else null
    }
  }

private suspend fun pictureExtentOf(picture: PictureBytes): Size? =
  walkPicture(picture) { extent() }?.takeIf { it.width > 0 && it.height > 0 }

private suspend fun PictureWalk.extent(): Size? =
  when (val container = container()) {
    PictureContainer.Jpeg -> jpegExtent()
    PictureContainer.Png -> pngExtent(block(0, minOf(size, PNG_HEADER_BYTES)).let { it.bytes.copyOf(it.end) })?.size
    PictureContainer.Webp -> webpExtent()
    PictureContainer.Tiff -> tiffExtent()
    PictureContainer.JxlCodestream, PictureContainer.JxlBoxed -> jxlHeaderOf(jxlCodestreamHead(container)).size
    PictureContainer.Heif -> heifMeta()?.let(::heifExtent)
    null -> null
  }

private val PngExtent.size: Size get() = Size(width, height)

private val JxlHeader.size: Size get() = Size(width, height)

// The sample precision, then the height and the width.
private suspend fun PictureWalk.jpegExtent(): Size? {
  forEachJpegSegment { marker, dataStart, end ->
    if (marker in SOF0..SOF15 && marker != DHT && marker != JPG && marker != DAC) {
      return Size(u16(dataStart + 3, limit = end), u16(dataStart + 1, limit = end))
    }
  }
  return null
}

// Only the first chunk is read, which an extended file must lead with and a simple file has only the one of.
private suspend fun PictureWalk.webpExtent(): Size? {
  val riff = riffEnd()
  val pos = WEBP_FIRST_CHUNK
  val data = pos + RIFF_CHUNK_HEADER
  val end = minOf(data + u32(pos + 4, little = true, limit = riff), riff)
  return when (u32(pos, limit = riff).toInt()) {
    WEBP_VP8X -> {
      Size(u24(data + 4, end) + 1, u24(data + 7, end) + 1)
    }
    VP8 -> {
      if (!matches(data + 3, VP8_START_CODE, end)) malformed()
      Size(
        u16(data + 6, little = true, limit = end) and VP8_SIDE,
        u16(data + 8, little = true, limit = end) and VP8_SIDE,
      )
    }
    VP8L -> {
      if (u8(data, end) != VP8L_SIGNATURE) malformed()
      val sides = u32(data + 1, little = true, limit = end)
      Size((sides and VP8L_SIDE).toInt() + 1, ((sides ushr VP8L_SIDE_BITS) and VP8L_SIDE).toInt() + 1)
    }
    else -> {
      null
    }
  }
}

private suspend fun PictureWalk.u24(
  at: Long,
  limit: Long,
): Int = u16(at, little = true, limit = limit) or (u8(at + 2, limit) shl 16)

// A width or length is a SHORT or a LONG, or in a BigTIFF a LONG8, held inside its entry.
private suspend fun PictureWalk.tiffExtent(): Size? {
  val directory = tiffDirectory(0, size)
  var width = 0L
  var height = 0L
  forEachTiffEntry(directory) { tag, entry ->
    if (tag == IMAGE_WIDTH || tag == IMAGE_LENGTH) {
      val at = directory.valueAt(entry)
      val value =
        when (u16(entry + 2, directory.little, size)) {
          TIFF_SHORT -> u16(at, directory.little).toLong()
          TIFF_LONG -> u32(at, directory.little)
          TIFF_LONG8 -> if (directory.big) u64(at, directory.little) else malformed()
          else -> malformed()
        }
      if (tag == IMAGE_WIDTH) width = value else height = value
      if (width > 0 && height > 0) {
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
        return Size(width.toInt(), height.toInt())
      }
    }
  }
  return null
}

private val VP8 = fourcc("VP8 ")
private val VP8L = fourcc("VP8L")
private val VP8_START_CODE = byteArrayOf(0x9D.toByte(), 0x01, 0x2A)

// The signature, then the IHDR chunk's length, type, 13 bytes of fields and CRC.
private const val PNG_HEADER_BYTES = 8L + 8 + 13 + 4
private const val VP8_SIDE = 0x3FFF
private const val VP8L_SIGNATURE = 0x2F
private const val VP8L_SIDE = 0x3FFFL
private const val VP8L_SIDE_BITS = 14
private const val SOF0 = 0xC0
private const val SOF15 = 0xCF
private const val DHT = 0xC4
private const val JPG = 0xC8
private const val DAC = 0xCC
private const val IMAGE_WIDTH = 256
private const val IMAGE_LENGTH = 257
private const val TIFF_SHORT = 3
private const val TIFF_LONG = 4
private const val TIFF_LONG8 = 16
