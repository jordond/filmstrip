package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.jpegProfileIsRgbOrGrey
import java.util.zip.CRC32

/**
 * This picture with every colour description Android's decoder would convert from hidden, every other byte where it
 * was.
 *
 * Android decodes a picture that describes its colour into a colour space built from that description, so reading its
 * pixels as sRGB converts them, and it converts the samples themselves when it cannot build a space, as with a lookup
 * table profile. It builds one from an ICC profile, from PNG `gAMA` or `cHRM` alone or together, and from an AVIF or
 * HEIC `prof`. PNG `cICP` and the primaries and transfer of `nclx` are hidden as well, so no API level converts from
 * them either. With every description hidden the decode holds the stored codes. Each format keeps its length and
 * layout:
 *
 * - PNG: the `iCCP`, `gAMA`, `cHRM` and `cICP` chunks are renamed to private chunks and their CRCs rewritten. An `sRGB`
 * chunk stays, since it decodes as no description at all.
 * - JPEG: every `APP2` segment holding a profile loses its `ICC_PROFILE` signature, when [jpegProfileIsRgbOrGrey]
 * holds. A CMYK or YCCK profile stays, since the decoder needs it to reach RGB at all.
 * - WebP: the `VP8X` header's profile flag is cleared and the `ICCP` chunk renamed.
 * - AVIF and HEIC: every `colr` property of type `prof` or `rICC` becomes a box of a type no reader knows, and loses
 * any essential mark its item associations give it, so a decoder skips it as an unknown property. It keeps its place
 * among the properties, so every association still points at the property it did. Every `nclx` property has its
 * transfer set to unspecified, and its primaries too unless [keepNclxPrimaries] is true. Its matrix coefficients and
 * range stay.
 *
 * Anything that is not one of these formats, and anything past the point a container stops parsing, comes back as it
 * was. A picture with nothing to hide comes back as this same array, and any other as a copy.
 *
 * @param keepNclxPrimaries Whether every `nclx` keeps its primaries, so a chromaticity derived matrix still reads as
 * the named matrix its primaries give.
 */
internal fun ByteArray.withColourDescriptionsHidden(keepNclxPrimaries: Boolean = false): ByteArray {
  val edit = Edit(this)
  when {
    startsWith(PNG_SIGNATURE) -> edit.hidePng()
    startsWith(JPEG_START) -> edit.hideJpeg()
    ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> edit.hideWebp()
    ascii(4, 4) == "ftyp" -> edit.hideIsobmff(keepNclxPrimaries)
  }
  return edit.result
}

// Reads from the source and writes to a copy made on the first write, so a picture with nothing to hide is never
// copied. Every write keeps the layout, so a position read from the source holds in the copy.
private class Edit(
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

private fun Edit.hidePng() {
  var at = PNG_SIGNATURE.size
  while (at + PNG_CHUNK_OVERHEAD <= source.size) {
    val length = source.u32be(at)
    if (length < 0 || length > source.size - at - PNG_CHUNK_OVERHEAD) return
    when (source.ascii(at + 4, 4)) {
      "IDAT", "IEND" -> return
      in PNG_COLOUR_CHUNKS -> hidePngChunk(at, length)
    }
    at += PNG_CHUNK_OVERHEAD + length
  }
}

private fun Edit.hidePngChunk(
  at: Int,
  length: Int,
) {
  // The second letter's case bit marks a chunk private, so a decoder skips it as one it does not know.
  this[at + 5] = (source[at + 5].toInt() or CASE_BIT).toByte()
  val crc = CRC32().apply { update(result, at + 4, 4 + length) }.value
  write(at + 8 + length, ByteArray(4) { (crc ushr (24 - 8 * it)).toByte() })
}

private fun Edit.hideJpeg() {
  if (!jpegProfileIsRgbOrGrey(source)) return
  var at = JPEG_START.size
  while (at + 4 <= source.size && source.u8(at) == MARKER) {
    val marker = source.u8(at + 1)
    if (marker == MARKER) {
      at += 1
      continue
    }
    if (marker == SOI || marker == TEM || marker in RST) {
      at += 2
      continue
    }
    if (marker == SOS || marker == EOI) break
    val length = source.u16be(at + 2)
    if (length < 2 || at + 2 + length > source.size) break
    // A profile segment holds the signature, then its sequence number and the segment count.
    if (marker == APP2 && length - 2 >= ICC_SIGNATURE.size + 2 && source.matches(at + 4, ICC_SIGNATURE)) {
      this[at + 4] = HIDDEN_LETTER
    }
    at += 2 + length
  }
}

private fun Edit.hideWebp() {
  var at = RIFF_HEADER
  while (at + RIFF_CHUNK_HEADER <= source.size) {
    val length = source.u32le(at + 4)
    if (length < 0 || length > source.size - at - RIFF_CHUNK_HEADER) return
    when (source.ascii(at, 4)) {
      "VP8X" -> {
        if (length > 0) this[at + RIFF_CHUNK_HEADER] = (source.u8(at + RIFF_CHUNK_HEADER) and ICC_FLAG.inv()).toByte()
      }
      "ICCP" -> {
        this[at] = HIDDEN_LETTER
      }
    }
    at += RIFF_CHUNK_HEADER + length + (length and 1)
  }
}

// A colr box whose colour type a decoder does not know fails the whole file on Android's HEIF reader before API 36,
// while a property box of a type it does not know is kept in its place and skipped.
private fun Edit.hideIsobmff(keepNclxPrimaries: Boolean) {
  val renamed = mutableSetOf<Int>()
  forEachColourProperty { index, type, payload ->
    when (type) {
      "prof", "rICC" -> {
        write(payload.typeAt, HIDDEN_BOX_TYPE)
        renamed += index
      }
      "nclx" -> {
        if (payload.end - payload.start >= NCLX_TRANSFER_END) {
          if (!keepNclxPrimaries) write(payload.start + NCLX_PRIMARIES, UNSPECIFIED_FIELD)
          write(payload.start + NCLX_TRANSFER, UNSPECIFIED_FIELD)
        }
      }
    }
  }
  if (renamed.isEmpty()) return
  val properties = propertiesBox() ?: return
  source.forEachBox(properties.start, properties.end) { type, payload ->
    if (type == "ipma") clearEssential(payload, renamed)
  }
}

// An ipma box is a full box holding an entry count, then per entry an item id of two bytes, or four from version 1,
// an association count and the associations. Each is a byte, or two when flag 1 is set, whose top bit marks the
// property essential and whose other bits give its one-based index among the ipco boxes.
private fun Edit.clearEssential(
  ipma: Payload,
  indices: Set<Int>,
) {
  if (ipma.end - ipma.start < FULL_BOX_HEADER + 4) return
  val idSize = if (source.u8(ipma.start) < 1) 2 else 4
  val associationSize = if (source.u8(ipma.start + 3) and 1 == 1) 2 else 1
  val indexMask = if (associationSize == 2) WIDE_INDEX_MASK else INDEX_MASK
  var entries = source.u32be(ipma.start + FULL_BOX_HEADER).toLong() and U32_MASK
  var at = ipma.start + FULL_BOX_HEADER + 4
  while (entries-- > 0 && at + idSize + 1 <= ipma.end) {
    at += idSize
    val count = source.u8(at)
    at += 1
    if (at + count * associationSize > ipma.end) return
    repeat(count) {
      val association = if (associationSize == 2) source.u16be(at) else source.u8(at)
      if (association and indexMask in indices) this[at] = (source.u8(at) and ESSENTIAL.inv()).toByte()
      at += associationSize
    }
  }
}

// The primary meta box's item properties box.
private fun Edit.propertiesBox(): Payload? {
  val meta = source.box(0, source.size, "meta") ?: return null
  // meta is a full box, so its children start past four bytes of version and flags.
  return source.box(meta.start + FULL_BOX_HEADER, meta.end, "iprp")
}

// Every colr property in the primary meta box's property container, as its one-based index among the container's
// boxes, its colour type and its payload.
private inline fun Edit.forEachColourProperty(each: Edit.(index: Int, type: String, payload: Payload) -> Unit) {
  val properties = propertiesBox() ?: return
  val container = source.box(properties.start, properties.end, "ipco") ?: return
  var index = 0
  source.forEachBox(container.start, container.end) { type, payload ->
    index++
    if (type == "colr" && payload.end - payload.start >= 4) each(index, source.ascii(payload.start, 4), payload)
  }
}

// Where a box's type and payload sit, the payload from its first byte up to the box's end.
private class Payload(
  val typeAt: Int,
  val start: Int,
  val end: Int,
)

private fun ByteArray.box(
  from: Int,
  to: Int,
  type: String,
): Payload? {
  var found: Payload? = null
  forEachBox(from, to) { boxType, payload -> if (found == null && boxType == type) found = payload }
  return found
}

private inline fun ByteArray.forEachBox(
  from: Int,
  to: Int,
  each: (type: String, payload: Payload) -> Unit,
) {
  var at = from
  while (at + BOX_HEADER <= to) {
    val declared = u32be(at).toLong() and U32_MASK
    val header = if (declared == 1L) LARGE_BOX_HEADER else BOX_HEADER
    val length =
      when (declared) {
        0L -> (to - at).toLong()
        1L -> if (at + LARGE_BOX_HEADER <= to) u64be(at + BOX_HEADER) else return
        else -> declared
      }
    if (length < header || length > to - at) return
    val end = at + length.toInt()
    each(ascii(at + 4, 4), Payload(at + 4, at + header, end))
    at = end
  }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matches(0, prefix)

private fun ByteArray.matches(
  at: Int,
  expected: ByteArray,
): Boolean = at >= 0 && at + expected.size <= size && expected.indices.all { this[at + it] == expected[it] }

private fun ByteArray.ascii(
  at: Int,
  length: Int,
): String = if (at >= 0 && at + length <= size) String(this, at, length, Charsets.ISO_8859_1) else ""

// Every caller checks the bytes it reads lie inside the array first.
private fun ByteArray.u8(at: Int): Int = this[at].toInt() and BYTE

private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u32be(at: Int): Int = (u16be(at) shl 16) or u16be(at + 2)

private fun ByteArray.u32le(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

private fun ByteArray.u64be(at: Int): Long =
  ((u32be(at).toLong() and U32_MASK) shl 32) or (u32be(at + 4).toLong() and U32_MASK)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private const val PNG_CHUNK_OVERHEAD = 12
private val PNG_COLOUR_CHUNKS = setOf("iCCP", "gAMA", "cHRM", "cICP")
private const val CASE_BIT = 0x20

private const val MARKER = 0xFF
private const val SOI = 0xD8
private val JPEG_START = byteArrayOf(MARKER.toByte(), SOI.toByte())
private const val TEM = 0x01
private val RST = 0xD0..0xD7
private const val SOS = 0xDA
private const val EOI = 0xD9
private const val APP2 = 0xE2
private val ICC_SIGNATURE = "ICC_PROFILE\u0000".encodeToByteArray()

private const val RIFF_HEADER = 12
private const val RIFF_CHUNK_HEADER = 8
private const val ICC_FLAG = 0x20

private const val BOX_HEADER = 8
private const val LARGE_BOX_HEADER = 16
private const val FULL_BOX_HEADER = 4
private val HIDDEN_BOX_TYPE = "xxxx".encodeToByteArray()
private const val ESSENTIAL = 0x80
private const val INDEX_MASK = 0x7F
private const val WIDE_INDEX_MASK = 0x7FFF

// The colour type, then two bytes each of primaries and transfer, as ITU-T H.273 numbers them.
private const val NCLX_PRIMARIES = 4
private const val NCLX_TRANSFER = 6
private const val NCLX_TRANSFER_END = 8
private val UNSPECIFIED_FIELD = byteArrayOf(0, 2)

private const val HIDDEN_LETTER = 'X'.code.toByte()
private const val BYTE = 0xFF
private const val U32_MASK = 0xFFFFFFFFL
