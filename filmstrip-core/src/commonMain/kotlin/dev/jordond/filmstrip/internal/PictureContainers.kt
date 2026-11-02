package dev.jordond.filmstrip.internal

// The containers a picture walk tells apart by their opening bytes, and the structures more than one walk steps
// through: the marker segments of a JPEG, the first image directory of a TIFF and the top level boxes of an AVIF, HEIC
// or JPEG XL.

/**
 * A picture container a [PictureWalk] knows how to step through.
 */
internal enum class PictureContainer {
  Jpeg,
  Png,
  Webp,
  Tiff,
  JxlCodestream,
  JxlBoxed,
  Heif,
}

/**
 * The container the walked picture opens as, or null for one no walk reads.
 */
internal suspend fun PictureWalk.container(): PictureContainer? =
  when {
    matches(0, JPEG_SIGNATURE) -> PictureContainer.Jpeg
    matches(0, PNG_SIGNATURE) -> PictureContainer.Png
    matches(0, RIFF_TAG) && matches(8, WEBP_TAG) -> PictureContainer.Webp
    TIFF_ORDERS.any { matches(0, it) } -> PictureContainer.Tiff
    matches(0, JXL_CODESTREAM) -> PictureContainer.JxlCodestream
    matches(0, JXL_CONTAINER) -> PictureContainer.JxlBoxed
    matches(4, FILE_TYPE) -> PictureContainer.Heif
    else -> null
  }

/**
 * Hands [visit] each marker segment of a JPEG ahead of its first scan, with its marker, where its data starts and
 * where it ends. Fill bytes ahead of a marker are skipped. A segment that cannot hold its own length throws
 * [MalformedPicture], and one the file cuts short ends at the end of the file.
 */
internal suspend inline fun PictureWalk.forEachJpegSegment(visit: (marker: Int, dataStart: Long, end: Long) -> Unit) {
  var pos = 2L
  while (true) {
    if (u8(pos) != 0xFF) malformed()
    while (u8(pos) == 0xFF) pos++
    val marker = u8(pos++)
    if (marker == JPEG_SOS || marker == JPEG_EOI) return
    if (marker in JPEG_RST0..JPEG_RST7 || marker == JPEG_TEM) continue
    val length = u16(pos)
    if (length < 2) malformed()
    val end = minOf(pos + length, size)
    visit(marker, pos + 2, end)
    pos = end
  }
}

/**
 * The first image directory of the TIFF block that starts at [base] and stops at [end], whose offsets count from
 * [base]. Its [count] entries start at [first].
 */
internal class TiffDirectory(
  val little: Boolean,
  val big: Boolean,
  val first: Long,
  val count: Long,
  val end: Long,
) {
  val entrySize: Int get() = if (big) BIG_TIFF_ENTRY else CLASSIC_TIFF_ENTRY

  /**
   * Where the value of the entry at [entry] sits when it fits inside the entry.
   */
  fun valueAt(entry: Long): Long = entry + if (big) 12 else 8
}

/**
 * Reads the header of the classic or BigTIFF block at [base], throwing [MalformedPicture] when it is neither or its
 * first directory lies outside [end].
 */
internal suspend fun PictureWalk.tiffDirectory(
  base: Long,
  end: Long,
): TiffDirectory {
  val little =
    when {
      matches(base, LITTLE_ENDIAN, end) -> true
      matches(base, BIG_ENDIAN, end) -> false
      else -> malformed()
    }
  val big =
    when (u16(base + 2, little, end)) {
      CLASSIC_TIFF -> false
      BIG_TIFF -> true
      else -> malformed()
    }
  if (big && u16(base + 4, little, end) != BIG_OFFSET_SIZE) malformed()
  val offset = if (big) u64(base + 8, little, end) else u32(base + 4, little, end)
  if (offset > end - base) malformed()
  val directory = base + offset
  val count = if (big) u64(directory, little, end) else u16(directory, little, end).toLong()
  return TiffDirectory(little, big, directory + if (big) 8 else 2, count, end)
}

/**
 * Hands [visit] the tag of each entry of [directory] in order, with where the entry starts. An entry past the end of
 * the block throws [MalformedPicture].
 */
internal suspend inline fun PictureWalk.forEachTiffEntry(
  directory: TiffDirectory,
  visit: (tag: Int, entry: Long) -> Unit,
) {
  var entry = directory.first
  var remaining = directory.count
  while (remaining-- > 0) {
    visit(u16(entry, directory.little, directory.end), entry)
    entry += directory.entrySize
  }
}

/**
 * Where the top level box at [pos] ends and how long its header is. A box of length zero runs to the end of the file.
 * One that runs past the end throws [MalformedPicture] unless [cutShort] holds it to the end instead.
 */
internal suspend fun PictureWalk.topLevelBox(
  pos: Long,
  cutShort: Boolean = false,
): Pair<Long, Int> {
  val declared = u32(pos)
  val header = if (declared == 1L) LARGE_BOX_HEADER else BOX_HEADER
  val length =
    when (declared) {
      0L -> size - pos
      1L -> u64(pos + 8)
      else -> declared
    }
  if (length < header) malformed()
  if (length > size - pos && !cutShort) malformed()
  return (if (length > size - pos) size else pos + length) to header
}

/**
 * The top level `meta` box of an AVIF or HEIC on its own, or null when the file has none.
 */
internal suspend fun PictureWalk.heifMeta(): ByteView? {
  var pos = 0L
  while (pos < size) {
    val (end, _) = topLevelBox(pos)
    if (u32(pos + 4).toInt() == META) return block(pos, end - pos)
    pos = end
  }
  return null
}

/**
 * The first [JXL_HEADER_BYTES] of the codestream of a bare or boxed JPEG XL, or as much of it as the file holds.
 *
 * A boxed file keeps its codestream in its `jxlc` box, or in its `jxlp` boxes joined in order past each one's four
 * byte index. A box running past the end of the file is read as far as it goes.
 */
internal suspend fun PictureWalk.jxlCodestreamHead(container: PictureContainer): ByteView {
  if (container == PictureContainer.JxlCodestream) return block(0, minOf(size, JXL_HEADER_BYTES.toLong()))
  val head = ByteArray(JXL_HEADER_BYTES)
  var gathered = 0
  var pos = 0L
  while (pos < size && gathered < head.size) {
    val (end, header) = topLevelBox(pos, cutShort = true)
    val start =
      when (u32(pos + 4).toInt()) {
        JXLC -> pos + header
        JXLP -> pos + header + 4
        else -> end
      }
    if (start < end) {
      val taken = minOf(end - start, (head.size - gathered).toLong()).toInt()
      block(start, taken.toLong()).bytes.copyInto(head, gathered, 0, taken)
      gathered += taken
    }
    pos = end
  }
  return ByteView(head, gathered)
}

/**
 * Where the RIFF of a WebP ends, which is where its size field says or the end of the file when that comes first.
 */
internal suspend fun PictureWalk.riffEnd(): Long = minOf(RIFF_CHUNK_HEADER + u32(4, little = true), size)

/**
 * How much of a JPEG XL codestream's head [jxlCodestreamHead] gathers, which its size header and orientation sit well
 * inside.
 */
internal const val JXL_HEADER_BYTES: Int = 64

/**
 * The size of a RIFF chunk's type and length.
 */
internal const val RIFF_CHUNK_HEADER: Long = 8L

/**
 * Where the first chunk of a WebP starts, past the RIFF header and the `WEBP` form type.
 */
internal const val WEBP_FIRST_CHUNK: Long = 12L

internal val WEBP_VP8X: Int = fourcc("VP8X")

private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val RIFF_TAG = ascii("RIFF")
private val WEBP_TAG = ascii("WEBP")
private val FILE_TYPE = ascii("ftyp")
private val TIFF_ORDERS = listOf("II*\u0000", "MM\u0000*", "II+\u0000", "MM\u0000+").map(::ascii)
private val LITTLE_ENDIAN = ascii("II")
private val BIG_ENDIAN = ascii("MM")
internal val JXL_CODESTREAM: ByteArray = byteArrayOf(0xFF.toByte(), 0x0A)
private val JXL_CONTAINER = byteArrayOf(0, 0, 0, 0x0C) + ascii("JXL ") + byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A)
private val JXLC = fourcc("jxlc")
private val JXLP = fourcc("jxlp")

private const val JPEG_SOS = 0xDA
private const val JPEG_EOI = 0xD9
private const val JPEG_RST0 = 0xD0
private const val JPEG_RST7 = 0xD7
private const val JPEG_TEM = 0x01
private const val CLASSIC_TIFF = 42
private const val BIG_TIFF = 43
private const val BIG_OFFSET_SIZE = 8
private const val CLASSIC_TIFF_ENTRY = 12
private const val BIG_TIFF_ENTRY = 20
private const val BOX_HEADER = 8
private const val LARGE_BOX_HEADER = 16
