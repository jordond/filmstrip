package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.ascii

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
 * own for anything to read one out of. A JPEG, PNG or WebP is named from its [PictureContainer], and a GIF or BMP,
 * which no reader in core steps through, from its own signature. Any other container is left unnamed.
 */
internal fun imageMediaTypeOf(header: ByteArray): String {
  val view = ByteView(header, header.size)
  return when (view.container()) {
    PictureContainer.Jpeg -> {
      "image/jpeg"
    }
    PictureContainer.Png -> {
      "image/png"
    }
    PictureContainer.Webp -> {
      "image/webp"
    }
    PictureContainer.Tiff, PictureContainer.JxlCodestream, PictureContainer.JxlBoxed, PictureContainer.Heif -> {
      ""
    }
    null -> {
      when {
        view.matches(0, GIF_SIGNATURE) -> "image/gif"
        view.matches(0, BMP_SIGNATURE) -> "image/bmp"
        else -> ""
      }
    }
  }
}

private val GIF_SIGNATURE = ascii("GIF8")
private val BMP_SIGNATURE = ascii("BM")
