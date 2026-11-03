package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import java.io.ByteArrayOutputStream
import java.io.File

// Writes an ICC profile, or an EXIF orientation, into a picture that carries none, the way a writer that keeps them
// lays them out. Neither ffmpeg build the tests run on writes a profile into a WebP, AVIF or HEIC, so these are what
// put one there.

/**
 * [webp] rewritten as an extended WebP carrying [profile] in an `ICCP` chunk.
 *
 * The chunk is added after a `VP8X` header flagging it. A file that already opens on `VP8X` has the flag set and the
 * chunk put straight after it.
 */
internal fun taggedWebp(
  webp: ByteArray,
  profile: ByteArray,
): ByteArray {
  val chunks = webp.copyOfRange(RIFF_HEADER, webp.size)
  val iccp = riffChunk("ICCP", profile)
  val body =
    if (chunks.decodeToString(0, 4) == "VP8X") {
      val header = chunks.copyOfRange(0, VP8X_CHUNK)
      header[CHUNK_HEADER] = (header[CHUNK_HEADER].toInt() or ICC_FLAG).toByte()
      header + iccp + chunks.copyOfRange(VP8X_CHUNK, chunks.size)
    } else {
      val width = OVERLAY_SIZE.width - 1
      val height = OVERLAY_SIZE.height - 1
      val flags = byteArrayOf((ICC_FLAG or ALPHA_FLAG).toByte(), 0, 0, 0)
      riffChunk("VP8X", flags + littleEndian(width, 3) + littleEndian(height, 3)) + iccp + chunks
    }
  return "RIFF".encodeToByteArray() + littleEndian(4 + body.size, 4) + "WEBP".encodeToByteArray() + body
}

/**
 * [jpeg] with an APP2 segment carrying [profile] after its JFIF header, which a JFIF reader wants first.
 */
internal fun taggedJpeg(
  jpeg: ByteArray,
  profile: ByteArray,
): ByteArray = jpeg.withSegment(APP2, "ICC_PROFILE".encodeToByteArray() + byteArrayOf(0, 1, 1) + profile)

/**
 * [jpeg] with an APP1 EXIF segment holding nothing but [orientation], after its JFIF header.
 *
 * The EXIF is big endian, one IFD with the single orientation entry.
 */
internal fun orientedJpeg(
  jpeg: ByteArray,
  orientation: Int,
): ByteArray {
  val entry = bigEndian(ORIENTATION_TAG, 2) + bigEndian(SHORT, 2) + bigEndian(1, 4) + bigEndian(orientation, 2)
  val tiff =
    "MM".encodeToByteArray() + bigEndian(TIFF_MAGIC, 2) + bigEndian(8, 4) + bigEndian(1, 2) + entry +
      ByteArray(2) + ByteArray(4)
  return jpeg.withSegment(APP1, "Exif".encodeToByteArray() + ByteArray(2) + tiff)
}

/**
 * [heif] with a `colr` box of type `prof` carrying [profile] added to its item properties and associated with its
 * primary item.
 *
 * Every box around the new one grows by what was added, and every item extent stored after the `meta` box moves by the
 * same. This reads the single-item AVIF ffmpeg writes and the single-item HEIC `sips` writes, with `iloc` versions zero
 * to two and offsets of four or eight bytes.
 */
internal fun taggedHeif(
  heif: ByteArray,
  profile: ByteArray,
): ByteArray {
  val meta = boxesIn(heif, 0, heif.size).single { it.type == "meta" }
  val children = boxesIn(heif, meta.start + FULL_BOX, meta.end)
  val iprp = children.single { it.type == "iprp" }
  val iloc = children.single { it.type == "iloc" }
  val pitm = children.single { it.type == "pitm" }
  val properties = boxesIn(heif, iprp.start + BOX_HEADER, iprp.end)
  val ipco = properties.single { it.type == "ipco" }
  val ipma = properties.single { it.type == "ipma" }

  val colr = box("colr", "prof".encodeToByteArray() + profile)
  val index = boxesIn(heif, ipco.start + BOX_HEADER, ipco.end).size + 1
  val primary = heif.uint(pitm.start + FULL_BOX, if (heif[pitm.start + BOX_HEADER].toInt() == 0) 2 else 4)
  val (entryEnd, countAt, wide) = associationsOf(heif, ipma, primary)
  val association = if (wide) bigEndian(index, 2) else byteArrayOf(index.toByte())
  val added = colr.size + association.size

  val patched = heif.copyOf()
  patched[countAt] = (patched[countAt] + 1).toByte()
  for ((grown, by) in listOf(meta to added, iprp to added, ipco to colr.size, ipma to association.size)) {
    patched.put(grown.start, grown.size + by, 4)
  }
  shiftExtents(patched, iloc, past = meta.end, by = added)

  val first = minOf(ipco.end, entryEnd)
  val second = maxOf(ipco.end, entryEnd)
  val firstInsert = if (first == ipco.end) colr else association
  val secondInsert = if (second == ipco.end) colr else association
  return patched.copyOfRange(0, first) + firstInsert + patched.copyOfRange(first, second) + secondInsert +
    patched.copyOfRange(second, patched.size)
}

private class Box(
  val type: String,
  val start: Int,
  val end: Int,
) {
  val size: Int get() = end - start
}

private fun boxesIn(
  bytes: ByteArray,
  start: Int,
  end: Int,
): List<Box> {
  val boxes = mutableListOf<Box>()
  var at = start
  while (at + BOX_HEADER <= end) {
    // A size of one puts a sixty-four bit size after the type, and zero runs the box to the end of its parent.
    val size =
      when (val short = bytes.uint(at, 4).toInt()) {
        1 -> bytes.uint(at + BOX_HEADER, 8).toInt()
        0 -> end - at
        else -> short
      }
    check(size >= BOX_HEADER && at + size <= end) { "a box at $at runs past its parent" }
    boxes += Box(bytes.decodeToString(at + 4, at + BOX_HEADER), at, at + size)
    at += size
  }
  return boxes
}

// Where [item]'s association list in [ipma] ends, where its count sits, and whether each association takes two bytes.
private fun associationsOf(
  bytes: ByteArray,
  ipma: Box,
  item: Long,
): Triple<Int, Int, Boolean> {
  val version = bytes[ipma.start + BOX_HEADER].toInt()
  val wide = bytes[ipma.start + BOX_HEADER + 3].toInt() and 1 == 1
  val idSize = if (version < 1) 2 else 4
  val entries = bytes.uint(ipma.start + FULL_BOX, 4)
  var at = ipma.start + FULL_BOX + 4
  repeat(entries.toInt()) {
    val id = bytes.uint(at, idSize)
    val countAt = at + idSize
    val end = countAt + 1 + (bytes[countAt].toInt() and 0xFF) * (if (wide) 2 else 1)
    if (id == item) return Triple(end, countAt, wide)
    at = end
  }
  error("the primary item $item has no property associations")
}

// Adds [by] to every file offset in [iloc] that points at or past [past]. A nonzero base offset is moved, and the
// extent offsets measured from it with it. An item with no base offset, or one of zero, holds absolute extent offsets,
// and each of those is moved. An extent stored in the meta box's own idat moves with nothing.
private fun shiftExtents(
  bytes: ByteArray,
  iloc: Box,
  past: Int,
  by: Int,
) {
  val version = bytes[iloc.start + BOX_HEADER].toInt()
  var at = iloc.start + FULL_BOX
  val offsetSize = bytes[at].toInt() shr 4 and 0xF
  val lengthSize = bytes[at].toInt() and 0xF
  val baseSize = bytes[at + 1].toInt() shr 4 and 0xF
  val indexSize = if (version > 0) bytes[at + 1].toInt() and 0xF else 0
  at += 2
  val idSize = if (version < 2) 2 else 4
  val items = bytes.uint(at, idSize).toInt()
  at += idSize

  fun moved(
    offset: Int,
    size: Int,
  ) {
    if (size == 0) return
    val value = bytes.uint(offset, size)
    if (value >= past) bytes.put(offset, value + by, size)
  }

  repeat(items) {
    at += idSize
    val construction = if (version > 0) bytes[at + 1].toInt() and 0xF else 0
    if (version > 0) at += 2
    at += 2
    val baseAt = at
    at += baseSize
    val extents = bytes.uint(at, 2).toInt()
    at += 2
    val based = baseSize > 0 && bytes.uint(baseAt, baseSize) != 0L
    if (construction == 0 && based) moved(baseAt, baseSize)
    repeat(extents) {
      at += indexSize
      if (construction == 0 && !based) moved(at, offsetSize)
      at += offsetSize + lengthSize
    }
  }
}

/**
 * The HEIC `sips` wrote of [dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN], with no profile in it, among the
 * fixtures [FfmpegExportFixture] downloads.
 */
internal fun FfmpegExportFixture.heicFixture(): File = File(fixtures, HEIC_FIXTURE)

/**
 * The file name of [heicFixture].
 */
internal const val HEIC_FIXTURE = "overlay_mid_green.heic"

private fun ByteArray.withSegment(
  marker: Int,
  payload: ByteArray,
): ByteArray {
  val length = payload.size + 2
  val segment = byteArrayOf(0xFF.toByte(), marker.toByte()) + bigEndian(length, 2)
  val jfif = this[3] == APP0.toByte()
  val at = if (jfif) 4 + uint(4, 2).toInt() else 2
  return copyOfRange(0, at) + segment + payload + copyOfRange(at, size)
}

private fun box(
  type: String,
  body: ByteArray,
): ByteArray = bigEndian(BOX_HEADER + body.size, 4) + type.encodeToByteArray() + body

private fun riffChunk(
  type: String,
  body: ByteArray,
): ByteArray {
  val out = ByteArrayOutputStream()
  out.write(type.encodeToByteArray())
  out.write(littleEndian(body.size, 4))
  out.write(body)
  if (body.size % 2 == 1) out.write(0)
  return out.toByteArray()
}

private fun ByteArray.uint(
  at: Int,
  size: Int,
): Long = (0 until size).fold(0L) { value, index -> value shl 8 or (this[at + index].toLong() and 0xFF) }

private fun ByteArray.put(
  at: Int,
  value: Number,
  size: Int,
) {
  val long = value.toLong()
  for (index in 0 until size) this[at + index] = (long shr (8 * (size - 1 - index))).toByte()
}

private fun bigEndian(
  value: Int,
  bytes: Int,
): ByteArray = ByteArray(bytes) { (value shr (8 * (bytes - 1 - it))).toByte() }

private fun littleEndian(
  value: Int,
  bytes: Int,
): ByteArray = ByteArray(bytes) { (value shr (8 * it)).toByte() }

// RIFF, its size and WEBP.
private const val RIFF_HEADER = 12

// A chunk's type and size.
private const val CHUNK_HEADER = 8

// A VP8X chunk's header and its ten byte body.
private const val VP8X_CHUNK = CHUNK_HEADER + 10
private const val ICC_FLAG = 0x20
private const val ALPHA_FLAG = 0x10

private const val APP0 = 0xE0
private const val APP1 = 0xE1
private const val APP2 = 0xE2
private const val ORIENTATION_TAG = 0x0112
private const val SHORT = 3
private const val TIFF_MAGIC = 42

// A box's size and type, and a full box's version and flags after them.
private const val BOX_HEADER = 8
private const val FULL_BOX = BOX_HEADER + 4
