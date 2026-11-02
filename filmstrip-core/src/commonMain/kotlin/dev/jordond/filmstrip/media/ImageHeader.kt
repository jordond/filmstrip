package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.ByteRangeRead
import dev.jordond.filmstrip.internal.pictureOrientationOf

// What a still's own bytes say about it, read once for the targets that have no platform reader to ask. Android and
// Apple ask theirs, which is authoritative there and already answers the same two questions, so only the JVM and a
// browser read the bytes through here.

/**
 * The EXIF orientation tag value of the orientation [picture] says it is shown in, or [EXIF_ORIENTATION_NORMAL] when
 * it says none.
 *
 * Reads through [pictureOrientationOf], so a JPEG, PNG, WebP, TIFF, AVIF, HEIC or JPEG XL all answer. A format with no
 * orientation, a file that stops short of its tag and a malformed block all read as normal rather than throwing.
 */
internal fun exifOrientationOf(picture: ByteArray): Int = pictureOrientationOf(picture).exif

/**
 * The EXIF orientation tag value of a still of [size] bytes that [read] fetches ranges of, or [EXIF_ORIENTATION_NORMAL]
 * when it says none.
 *
 * Only the container's own headers and the block holding the tag are fetched, wherever in the file the container puts
 * them, so a tag written after the pixels is found without reading the pixels.
 */
internal suspend fun exifOrientationOf(
  size: Long,
  read: ByteRangeRead,
): Int = pictureOrientationOf(size, read).exif

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

private fun ByteArray.startsWith(
  prefix: ByteArray,
  at: Int = 0,
): Boolean {
  if (at < 0 || at > size - prefix.size) return false

  return prefix.indices.all { this[at + it] == prefix[it] }
}

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val GIF_MAGIC = "GIF8".encodeToByteArray()
private val BMP_MAGIC = "BM".encodeToByteArray()
private val RIFF_MAGIC = "RIFF".encodeToByteArray()
private val WEBP_TAG = "WEBP".encodeToByteArray()
private const val WEBP_TAG_OFFSET = 8
