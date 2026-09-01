package dev.jordond.filmstrip.media

// What a still's own first bytes say about it, read once for the targets that have no platform
// reader to ask. Android and Apple ask theirs, which is authoritative there and already answers the
// same two questions, so only the JVM and a browser read the bytes through here.

/**
 * The EXIF orientation tag [header] carries, or [EXIF_ORIENTATION_NORMAL] when it carries none.
 *
 * [header] is the head of a file rather than all of it, because the block a camera writes sits ahead
 * of the pixels. A format with no EXIF block, a read that stopped short and a malformed block all
 * read as normal rather than throwing.
 */
internal fun exifOrientationOf(header: ByteArray): Int {
  if (!header.startsWith(SOI)) return EXIF_ORIENTATION_NORMAL

  var at = SOI.size
  while (at + MARKER_BYTES <= header.size) {
    if ((header[at].toInt() and BYTE_MASK) != MARKER_FILL) return EXIF_ORIENTATION_NORMAL

    when (val marker = header[at + 1].toInt() and BYTE_MASK) {
      // Padding ahead of the marker, which is legal and is stepped over rather than read as one.
      MARKER_FILL -> {
        at++
      }
      // Pixels start here, and nothing past them is a header.
      START_OF_SCAN, END_OF_IMAGE -> {
        return EXIF_ORIENTATION_NORMAL
      }
      in STANDALONE_MARKERS -> {
        at += MARKER_BYTES
      }
      else -> {
        val length = header.shortAt(at + MARKER_BYTES, bigEndian = true) ?: return EXIF_ORIENTATION_NORMAL
        if (length < LENGTH_BYTES) return EXIF_ORIENTATION_NORMAL

        val payload = at + MARKER_BYTES + LENGTH_BYTES
        if (marker == APP1 && header.startsWith(EXIF_IDENTIFIER, payload)) {
          return header.orientationInTiff(payload + EXIF_IDENTIFIER.size)
        }
        at = payload + length - LENGTH_BYTES
      }
    }
  }

  return EXIF_ORIENTATION_NORMAL
}

/**
 * How much of a file's head [exifOrientationOf] is given, which is the most an APP1 segment can be.
 *
 * A reader hands over a prefix rather than a whole photo, and every one of them hands over the same
 * prefix, so no target can find a tag another one read past the end of.
 */
internal const val IMAGE_HEADER_BYTES: Int = 64 * 1024

/**
 * Whether a file opening with [prefix] can carry an EXIF block, which only a JPEG does.
 *
 * [EXIF_MAGIC_BYTES] is enough to answer it, so a reader pulling a header for nothing but
 * [exifOrientationOf] can stop there for every other format instead of taking
 * [IMAGE_HEADER_BYTES] off a picture no tag can be in.
 */
internal fun carriesExif(prefix: ByteArray): Boolean = prefix.startsWith(SOI)

/**
 * How much of a file's head [carriesExif] reads.
 */
internal val EXIF_MAGIC_BYTES: Int get() = SOI.size

/**
 * The media type [header] identifies itself as, such as `image/jpeg`, or empty when nothing here
 * recognises it.
 *
 * Read off the magic bytes, because a source handed over as bytes carries no name and no type of its
 * own for anything to read one out of.
 */
internal fun imageMediaTypeOf(header: ByteArray): String =
  when {
    header.startsWith(JPEG_MAGIC) -> "image/jpeg"
    header.startsWith(PNG_MAGIC) -> "image/png"
    header.startsWith(GIF_MAGIC) -> "image/gif"
    header.startsWith(BMP_MAGIC) -> "image/bmp"
    header.startsWith(RIFF_MAGIC) && header.startsWith(WEBP_TAG, WEBP_TAG_OFFSET) -> "image/webp"
    else -> ""
  }

/**
 * The orientation in the TIFF block starting at [start], the first entry of whose first directory
 * the tag sits in.
 */
private fun ByteArray.orientationInTiff(start: Int): Int {
  val bigEndian =
    when {
      startsWith(BIG_ENDIAN, start) -> true
      startsWith(LITTLE_ENDIAN, start) -> false
      else -> return EXIF_ORIENTATION_NORMAL
    }

  if (shortAt(start + ORDER_BYTES, bigEndian) != TIFF_MAGIC) return EXIF_ORIENTATION_NORMAL

  val offset = intAt(start + ORDER_BYTES + SHORT_BYTES, bigEndian) ?: return EXIF_ORIENTATION_NORMAL
  val directory = start + offset
  if (offset < 0 || directory < start) return EXIF_ORIENTATION_NORMAL

  val entries = shortAt(directory, bigEndian) ?: return EXIF_ORIENTATION_NORMAL
  for (entry in 0 until entries) {
    val at = directory + SHORT_BYTES + entry * ENTRY_BYTES
    val tag = shortAt(at, bigEndian) ?: return EXIF_ORIENTATION_NORMAL
    if (tag != ORIENTATION_TAG) continue

    // A tag typed anything else has its value somewhere other than where a short's sits, so reading
    // one out of these two bytes would be reading a coincidence.
    if (shortAt(at + TAG_BYTES, bigEndian) != TYPE_SHORT) return EXIF_ORIENTATION_NORMAL
    return shortAt(at + TAG_BYTES + TYPE_BYTES + COUNT_BYTES, bigEndian) ?: EXIF_ORIENTATION_NORMAL
  }

  return EXIF_ORIENTATION_NORMAL
}

private fun ByteArray.startsWith(
  prefix: ByteArray,
  at: Int = 0,
): Boolean {
  if (at < 0 || at > size - prefix.size) return false

  return prefix.indices.all { this[at + it] == prefix[it] }
}

private fun ByteArray.shortAt(
  offset: Int,
  bigEndian: Boolean,
): Int? {
  if (offset < 0 || offset > size - SHORT_BYTES) return null
  val first = this[offset].toInt() and BYTE_MASK
  val second = this[offset + 1].toInt() and BYTE_MASK

  return if (bigEndian) (first shl BITS_PER_BYTE) or second else (second shl BITS_PER_BYTE) or first
}

private fun ByteArray.intAt(
  offset: Int,
  bigEndian: Boolean,
): Int? {
  val first = shortAt(offset, bigEndian) ?: return null
  val second = shortAt(offset + SHORT_BYTES, bigEndian) ?: return null

  return if (bigEndian) {
    (first shl SHORT_BITS) or second
  } else {
    (second shl SHORT_BITS) or first
  }
}

private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
private val EXIF_IDENTIFIER = "Exif\u0000\u0000".encodeToByteArray()
private val BIG_ENDIAN = "MM".encodeToByteArray()
private val LITTLE_ENDIAN = "II".encodeToByteArray()

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val GIF_MAGIC = "GIF8".encodeToByteArray()
private val BMP_MAGIC = "BM".encodeToByteArray()
private val RIFF_MAGIC = "RIFF".encodeToByteArray()
private val WEBP_TAG = "WEBP".encodeToByteArray()
private const val WEBP_TAG_OFFSET = 8

private const val MARKER_FILL = 0xFF
private const val APP1 = 0xE1
private const val START_OF_SCAN = 0xDA
private const val END_OF_IMAGE = 0xD9

/**
 * The markers that carry no length, so the next one follows immediately.
 */
private val STANDALONE_MARKERS = setOf(0x01) + (0xD0..0xD8)

private const val TIFF_MAGIC = 42
private const val ORIENTATION_TAG = 0x0112
private const val TYPE_SHORT = 3

private const val MARKER_BYTES = 2
private const val LENGTH_BYTES = 2
private const val ORDER_BYTES = 2
private const val SHORT_BYTES = 2
private const val TAG_BYTES = 2
private const val TYPE_BYTES = 2
private const val COUNT_BYTES = 4
private const val ENTRY_BYTES = 12

private const val BITS_PER_BYTE = 8
private const val SHORT_BITS = 16
private const val BYTE_MASK = 0xFF
