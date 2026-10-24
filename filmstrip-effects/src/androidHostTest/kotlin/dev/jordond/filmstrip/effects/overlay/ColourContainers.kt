package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.test.IccPictures
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.assertEquals

// A real Display P3 profile, so the reader parses it as RGB.
internal val PROFILE = IccPictures.DISPLAY_P3.profile

internal val ICCP_PAYLOAD = "test".encodeToByteArray() + byteArrayOf(0, 0) + deflated(PROFILE)

// Display P3 primaries and the sRGB transfer, as ITU-T H.273 numbers them, then a matrix and full range.
internal val P3_CICP = byteArrayOf(12, 13, 0, 1)

internal val VP8X_FLAGGED = byteArrayOf(0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0)

// Past the start marker, the segment's marker and its length.
internal const val SEGMENT_SIGNATURE_AT = 6

internal const val PNG_SIGNATURE_SIZE = 8
internal val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal fun profileOf(colourSpace: String): ByteArray =
  be32(132) + ByteArray(12) + colourSpace.encodeToByteArray() + ByteArray(112) { (it * 7).toByte() }

// A one texel PNG with [chunks] between its header and its image, written here so no encoder adds chunks of its own.
internal fun png(vararg chunks: ByteArray): ByteArray {
  val header = be32(1) + be32(1) + byteArrayOf(8, 6, 0, 0, 0)
  val image = deflated(byteArrayOf(0, 0x40, 0x80.toByte(), 0xC0.toByte(), 0xFF.toByte()))
  return PNG_SIGNATURE + chunk("IHDR", header) + chunks.fold(ByteArray(0)) { all, chunk -> all + chunk } +
    chunk("IDAT", image) + chunk("IEND", ByteArray(0))
}

internal fun jpeg(segments: ByteArray): ByteArray =
  byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments +
    byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())

internal fun jpegProfile(
  part: ByteArray,
  sequence: Int = 1,
  count: Int = 1,
): ByteArray {
  val payload = "ICC_PROFILE".encodeToByteArray() + byteArrayOf(0, sequence.toByte(), count.toByte()) + part
  return byteArrayOf(0xFF.toByte(), 0xE2.toByte()) + be16(2 + payload.size) + payload
}

internal fun webp(chunks: ByteArray): ByteArray =
  "RIFF".encodeToByteArray() + le32(4 + chunks.size) + "WEBP".encodeToByteArray() + chunks

// An HEIF whose primary item takes the properties [ipma] gives it, by default the first of [properties], where a test
// puts the profile.
internal fun heif(
  properties: ByteArray,
  ipma: ByteArray = ipma(0, false, 0x81),
): ByteArray {
  val meta = fullBox("meta", fullBox("pitm", be16(1)) + box("iprp", box("ipco", properties) + ipma))
  return box("ftyp", "heic".encodeToByteArray() + be32(0) + "mif1".encodeToByteArray()) + meta
}

// An ipma box giving item 1 [associations], each of two bytes when [wide] and one otherwise, the item id taking four
// bytes from version 1.
internal fun ipma(
  version: Int,
  wide: Boolean,
  vararg associations: Int,
): ByteArray {
  val id = if (version < 1) be16(1) else be32(1)
  val written = associations.fold(ByteArray(0)) { all, it -> all + if (wide) be16(it) else byteArrayOf(it.toByte()) }
  val header = byteArrayOf(version.toByte(), 0, 0, if (wide) 1 else 0)
  return box("ipma", header + be32(1) + id + byteArrayOf(associations.size.toByte()) + written)
}

// A version 0 ipma box giving each item one association, its id beside the one byte association.
internal fun ipmaOfItems(entries: List<Pair<Int, Int>>): ByteArray {
  val written =
    entries.fold(ByteArray(0)) { all, (item, association) -> all + be16(item) + byteArrayOf(1, association.toByte()) }
  return box("ipma", ByteArray(4) + be32(entries.size) + written)
}

internal fun ByteArray.lastIndexOf(type: String): Int = String(this, Charsets.ISO_8859_1).lastIndexOf(type)

internal fun assertEveryCrcHolds(png: ByteArray) {
  png.forEachChunk { at, length ->
    val crc = CRC32().apply { update(png, at + 4, 4 + length) }.value
    assertEquals(crc.toInt(), png.int32(at + 8 + length), "chunk ${png.typeAt(at)} has a stale CRC")
  }
}

internal fun ByteArray.chunkTypes(): List<String> = buildList { forEachChunk { at, _ -> add(typeAt(at)) } }

internal inline fun ByteArray.forEachChunk(each: (at: Int, length: Int) -> Unit) {
  var at = PNG_SIGNATURE_SIZE
  while (at < size) {
    val length = int32(at)
    each(at, length)
    at += 12 + length
  }
}

internal fun ByteArray.typeAt(at: Int): String = String(this, at + 4, 4, Charsets.ISO_8859_1)

internal fun ByteArray.indexOf(type: String): Int = String(this, Charsets.ISO_8859_1).indexOf(type)

internal fun ByteArray.int32(at: Int): Int =
  (0 until 4).fold(0) { value, index -> value shl 8 or (this[at + index].toInt() and 0xFF) }

internal fun chunk(
  type: String,
  data: ByteArray,
): ByteArray {
  val body = type.encodeToByteArray() + data
  val crc = CRC32().apply { update(body) }.value
  return be32(data.size) + body + be32(crc.toInt())
}

internal fun deflated(bytes: ByteArray): ByteArray {
  val deflater =
    Deflater().apply {
      setInput(bytes)
      finish()
    }
  val out = ByteArray(bytes.size * 2 + 64)
  val length = deflater.deflate(out)
  deflater.end()
  return out.copyOf(length)
}

internal fun box(
  type: String,
  payload: ByteArray,
): ByteArray = be32(8 + payload.size) + type.encodeToByteArray() + payload

internal fun fullBox(
  type: String,
  payload: ByteArray,
): ByteArray = box(type, ByteArray(4) + payload)

internal fun riffChunk(
  fourcc: String,
  payload: ByteArray,
): ByteArray = fourcc.encodeToByteArray() + le32(payload.size) + payload + ByteArray(payload.size % 2)

internal fun be16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

internal fun be32(value: Int): ByteArray = be16(value shr 16) + be16(value)

internal fun le32(value: Int): ByteArray = be32(value).reversedArray()
