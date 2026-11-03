package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.heif.Box
import dev.jordond.filmstrip.picture.heif.COLR
import dev.jordond.filmstrip.picture.heif.NCLX_TYPE
import dev.jordond.filmstrip.picture.heif.PROF
import dev.jordond.filmstrip.picture.heif.RICC
import dev.jordond.filmstrip.picture.heif.associationIndex
import dev.jordond.filmstrip.picture.heif.forEachEntry
import dev.jordond.filmstrip.picture.heif.metaParts
import dev.jordond.filmstrip.picture.png.CHRM
import dev.jordond.filmstrip.picture.png.CICP
import dev.jordond.filmstrip.picture.png.GAMA
import dev.jordond.filmstrip.picture.png.ICCP_PNG
import dev.jordond.filmstrip.picture.png.PNG_IDAT
import dev.jordond.filmstrip.picture.png.PNG_IEND
import dev.jordond.filmstrip.picture.png.PNG_SIGNATURE
import dev.jordond.filmstrip.picture.png.PngChunk
import dev.jordond.filmstrip.picture.png.pngChunk

/**
 * This picture with every color description a decoder could convert its samples from hidden, every other byte where
 * it was, so a decode holds the stored codes.
 *
 * The descriptions hidden are an ICC profile, PNG `gAMA`, `cHRM` and `cICP`, and the primaries and transfer of an
 * AVIF or HEIC `nclx`. Each format keeps its length and layout:
 *
 * - PNG: the `iCCP`, `gAMA`, `cHRM` and `cICP` chunks ahead of the first `IDAT` are renamed to private chunks and
 * their CRCs rewritten. An `sRGB` chunk stays, since it decodes as no description at all.
 * - JPEG: every `APP2` segment holding a profile loses its `ICC_PROFILE` signature, when [jpegProfileIsRgbOrGrey]
 * holds. A CMYK or YCCK profile stays, since a decoder needs it to reach RGB at all.
 * - WebP: the `VP8X` header's profile flag is cleared and the `ICCP` chunk renamed.
 * - AVIF and HEIC: every `colr` property of type `prof` or `rICC` becomes a box of a type no reader knows, and loses
 * any essential mark its item associations give it, so a decoder skips it as an unknown property. It keeps its place
 * among the properties, so every association still points at the property it did. Every `nclx` property has its
 * transfer set to unspecified, and its primaries too unless [keepNclxPrimaries] is true. Its matrix coefficients and
 * range stay.
 *
 * The picture is classified by [pictureContainerOf]. Anything that is not one of these formats, and anything past the
 * point its container stops parsing, comes back as it was. A picture with nothing to hide comes back as this same
 * array, and any other as a copy.
 *
 * @param keepNclxPrimaries Whether every `nclx` keeps its primaries, so a chromaticity derived matrix still reads as
 * the named matrix its primaries give.
 */
@InternalFilmstripApi
public fun ByteArray.withColorDescriptionsHidden(keepNclxPrimaries: Boolean = false): ByteArray {
  val edit = DescriptionEdit(this)
  val picture = ByteView(this, size)
  deferring {
    when (picture.container()) {
      PictureContainer.Png -> edit.hidePng(picture)
      PictureContainer.Jpeg -> edit.hideJpeg(picture)
      PictureContainer.Webp -> edit.hideWebp(picture)
      PictureContainer.Heif -> edit.hideHeif(picture, keepNclxPrimaries)
      PictureContainer.Tiff, PictureContainer.JxlCodestream, PictureContainer.JxlBoxed, null -> Unit
    }
  }
  return edit.result
}

// Reads from the source and writes to a copy made on the first write, so a picture with nothing to hide is never
// copied. Every write keeps the layout, so a position read from the source holds in the copy. A walk that stops at a
// malformed piece keeps every write made ahead of it.
private class DescriptionEdit(
  val source: ByteArray,
) {
  var result: ByteArray = source
    private set

  operator fun set(
    at: Int,
    value: Byte,
  ) {
    if (result[at] == value) return
    if (result === source) result = source.copyOf()
    result[at] = value
  }

  fun write(
    at: Int,
    bytes: ByteArray,
  ) {
    for (index in bytes.indices) this[at + index] = bytes[index]
  }
}

private fun DescriptionEdit.hidePng(png: ByteView) {
  var chunk = png.pngChunk(PNG_SIGNATURE.size)
  while (chunk.type != PNG_IDAT && chunk.type != PNG_IEND) {
    if (chunk.type in PNG_HIDDEN_CHUNKS) hidePngChunk(chunk)
    chunk = png.pngChunk(chunk.next)
  }
}

private fun DescriptionEdit.hidePngChunk(chunk: PngChunk) {
  val type = chunk.start - 4
  // The second letter's case bit marks a chunk private, so a decoder skips it as one it does not know.
  this[type + 1] = (source[type + 1].toInt() or CASE_BIT).toByte()
  val crc = ByteView(result, result.size).crc32(type, chunk.end)
  write(chunk.end, ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() })
}

private fun DescriptionEdit.hideJpeg(jpeg: ByteView) {
  if (!jpegProfileIsRgbOrGrey(source)) return
  walkJpeg(jpeg) { marker, _, dataStart, end ->
    if (jpeg.isProfileSegment(marker, dataStart, end)) this[dataStart] = HIDDEN_LETTER
  }
}

private fun DescriptionEdit.hideWebp(file: ByteView) {
  val webp = file.within(minOf(RIFF_CHUNK_HEADER + file.u32(4, little = true), file.end.toLong()).toInt())
  var pos = WEBP_FIRST_CHUNK.toInt()
  while (pos < webp.end) {
    val size = webp.u32(pos + 4, little = true)
    val end = webp.span(pos + RIFF_CHUNK_HEADER.toInt(), size)
    when (webp.u32(pos).toInt()) {
      WEBP_VP8X -> {
        val flags = pos + RIFF_CHUNK_HEADER.toInt()
        if (size > 0) this[flags] = (webp.u8(flags) and VP8X_ICC_FLAG.inv()).toByte()
      }
      ICCP_WEBP -> {
        this[pos] = HIDDEN_LETTER
      }
    }
    pos = end + (size and 1L).toInt()
  }
}

// A colr box whose color type a decoder does not know fails the whole file on Android's HEIF reader before API 36,
// while a property box of a type it does not know is kept in its place and skipped.
private fun DescriptionEdit.hideHeif(
  file: ByteView,
  keepNclxPrimaries: Boolean,
) {
  val parts = file.metaParts(lenient = true) ?: return
  val renamed = mutableSetOf<Int>()
  parts.properties.forEachIndexed { at, property ->
    if (property.type != COLR || property.end - property.start < 4) return@forEachIndexed
    when (file.u32(property.start).toInt()) {
      PROF, RICC -> {
        write(property.at + 4, HIDDEN_BOX_TYPE)
        renamed += at + 1
      }
      NCLX_TYPE -> {
        if (property.end - property.start >= NCLX_TRANSFER_END) {
          if (!keepNclxPrimaries) write(property.start + NCLX_PRIMARIES, UNSPECIFIED_FIELD)
          write(property.start + NCLX_TRANSFER, UNSPECIFIED_FIELD)
        }
      }
    }
  }
  if (renamed.isEmpty()) return
  for (ipma in parts.ipmas) deferring { clearEssential(file.within(ipma.end), ipma, renamed) }
}

// Each association is a byte, or two when the ipma's flag 1 is set, whose top bit marks the property essential.
private fun DescriptionEdit.clearEssential(
  file: ByteView,
  ipma: Box,
  indices: Set<Int>,
) {
  val associationSize = if (file.u8(ipma.start + 3) and 1 == 1) 2 else 1
  file.forEachEntry(ipma) { _, first, count ->
    for (at in 0 until count) {
      val association = first + at * associationSize
      if (file.associationIndex(ipma, first, at) in indices) {
        this[association] = (file.u8(association) and ESSENTIAL.inv()).toByte()
      }
    }
    true
  }
}

private val PNG_HIDDEN_CHUNKS = setOf(ICCP_PNG, GAMA, CHRM, CICP)
private const val CASE_BIT = 0x20

private val HIDDEN_BOX_TYPE = ascii("xxxx")
private const val ESSENTIAL = 0x80

// The color type, then two bytes each of primaries and transfer, as ITU-T H.273 numbers them.
private const val NCLX_PRIMARIES = 4
private const val NCLX_TRANSFER = 6
private const val NCLX_TRANSFER_END = 8
private val UNSPECIFIED_FIELD = byteArrayOf(0, 2)

private const val HIDDEN_LETTER = 'X'.code.toByte()
