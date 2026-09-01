package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The still every target's probe test measures, and the answer every one of them has to give back.
// Both live here so the four targets cannot drift apart on the size, on the length, or on any of
// the fields imageMediaInfoOf settles.

/**
 * Landscape and not a square, so a target that swapped the two sides would fail rather than pass by
 * coincidence.
 */
internal val IMAGE_PROBE_SIZE: Size = Size(64, 40)

internal val IMAGE_PROBE_DURATION: Duration = 5.seconds

/**
 * What a probe of [imageProbeBytes] has to answer with, given what this target calls the format.
 */
internal fun expectedImageInfo(format: String): MediaInfo =
  imageMediaInfoOf(
    codedSize = IMAGE_PROBE_SIZE,
    exifOrientation = EXIF_ORIENTATION_NORMAL,
    format = format,
    duration = IMAGE_PROBE_DURATION,
  )

/**
 * An uncompressed 24-bit bitmap of [IMAGE_PROBE_SIZE], written by hand.
 *
 * Every target gets the same bytes rather than one each from its own encoder, which is what makes
 * a disagreement between two probes a disagreement about the probe. Uncompressed, because that
 * needs no codec to produce and every target's image reader opens it.
 */
internal fun imageProbeBytes(): ByteArray {
  val width = IMAGE_PROBE_SIZE.width
  val height = IMAGE_PROBE_SIZE.height
  val rowBytes = (width * BYTES_PER_PIXEL + ROW_ALIGNMENT - 1) / ROW_ALIGNMENT * ROW_ALIGNMENT
  val pixels = rowBytes * height
  val file = ByteArray(HEADER_BYTES + pixels)

  file[0] = 'B'.code.toByte()
  file[1] = 'M'.code.toByte()
  file.putInt(2, file.size)
  file.putInt(BITMAP_DATA_OFFSET, HEADER_BYTES)
  file.putInt(FILE_HEADER_BYTES, INFO_HEADER_BYTES)
  file.putInt(FILE_HEADER_BYTES + 4, width)
  file.putInt(FILE_HEADER_BYTES + 8, height)
  file.putShort(FILE_HEADER_BYTES + 12, 1)
  file.putShort(FILE_HEADER_BYTES + 14, BYTES_PER_PIXEL * BITS_PER_BYTE)
  file.putInt(FILE_HEADER_BYTES + 20, pixels)

  // A flat mid grey. The colours are never asserted on, only the bounds, but a reader that
  // rejected an all-zero buffer would be a confusing way to find that out.
  for (index in HEADER_BYTES until file.size) file[index] = GREY

  return file
}

private fun ByteArray.putInt(
  offset: Int,
  value: Int,
) {
  for (byte in 0 until 4) this[offset + byte] = (value shr (byte * BITS_PER_BYTE)).toByte()
}

private fun ByteArray.putShort(
  offset: Int,
  value: Int,
) {
  for (byte in 0 until 2) this[offset + byte] = (value shr (byte * BITS_PER_BYTE)).toByte()
}

private const val FILE_HEADER_BYTES = 14
private const val INFO_HEADER_BYTES = 40
private const val HEADER_BYTES = FILE_HEADER_BYTES + INFO_HEADER_BYTES
private const val BITMAP_DATA_OFFSET = 10
private const val BYTES_PER_PIXEL = 3
private const val ROW_ALIGNMENT = 4
private const val BITS_PER_BYTE = 8
private const val GREY: Byte = 0x7F
