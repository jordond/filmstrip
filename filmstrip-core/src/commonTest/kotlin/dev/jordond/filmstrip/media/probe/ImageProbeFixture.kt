package dev.jordond.filmstrip.media.probe

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.imageMediaInfoOf
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The still every target's probe test measures, and the answer every one of them has to give back.
// Both live here so the four targets cannot drift apart on the size, on the length, or on any of
// the fields imageMediaInfoOf settles. A package of its own, because Android measures a still on a
// device and only what a device runs can be compiled into one.

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
 * The EXIF orientation [rotatedImageProbeBytes] carries, a quarter turn clockwise, which is how a
 * phone stores a photo taken in portrait.
 */
internal const val ROTATED_IMAGE_PROBE_ORIENTATION: Int = 6

/**
 * The EXIF orientation that mirrors as well as turning, which is the middle of the range a reader
 * treating the tag as a count of quarter turns would get wrong.
 */
internal const val MIRRORED_IMAGE_PROBE_ORIENTATION: Int = 5

/**
 * What a probe of [rotatedImageProbeBytes] has to answer with, given what this target calls the
 * format.
 *
 * The bounds are the stored ones, the same landscape [IMAGE_PROBE_SIZE] the unrotated still reports,
 * and the turn comes off the tag rather than off the bounds.
 */
internal fun expectedRotatedImageInfo(
  format: String,
  orientation: Int = ROTATED_IMAGE_PROBE_ORIENTATION,
): MediaInfo =
  imageMediaInfoOf(
    codedSize = IMAGE_PROBE_SIZE,
    exifOrientation = orientation,
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

/**
 * [BASELINE_JPEG] with a hand-written EXIF block spliced in, tagging it [orientation].
 *
 * The block goes ahead of every other segment, which is where a camera writes it. Written out field
 * by field rather than baked into the constant, so the orientation a target is being asked to read
 * is a number in the fixture and another orientation, or the other byte order a camera may have
 * written it in, comes off the same helper.
 *
 * @param orientation The EXIF orientation tag the block carries.
 * @param bigEndian Which way round the TIFF block inside it is written. The segment around it is
 *   big endian either way, because a JPEG's own headers always are.
 */
internal fun rotatedImageProbeBytes(
  orientation: Int = ROTATED_IMAGE_PROBE_ORIENTATION,
  bigEndian: Boolean = true,
): ByteArray {
  val baseline = baselineJpeg()

  return baseline.copyOfRange(0, SOI_BYTES) +
    exifSegment(orientation, bigEndian) +
    baseline.copyOfRange(SOI_BYTES, baseline.size)
}

/**
 * An APP1 segment whose first directory holds one entry, the orientation tag, and nothing else.
 */
private fun exifSegment(
  orientation: Int,
  bigEndian: Boolean,
): ByteArray {
  val segment = ByteArray(SEGMENT_BYTES)

  segment.putShort(0, APP1_MARKER, bigEndian = true)
  segment.putShort(MARKER_BYTES, SEGMENT_BYTES - MARKER_BYTES, bigEndian = true)
  EXIF_IDENTIFIER.copyInto(segment, IDENTIFIER_OFFSET)

  segment.putShort(TIFF_OFFSET, if (bigEndian) MOTOROLA else INTEL, bigEndian = true)
  segment.putShort(TIFF_OFFSET + 2, TIFF_MAGIC, bigEndian)
  segment.putInt(TIFF_OFFSET + 4, TIFF_HEADER_BYTES, bigEndian)

  segment.putShort(DIRECTORY_OFFSET, 1, bigEndian)
  segment.putShort(ENTRY_OFFSET, ORIENTATION_TAG, bigEndian)
  segment.putShort(ENTRY_OFFSET + 2, TYPE_SHORT, bigEndian)
  segment.putInt(ENTRY_OFFSET + 4, 1, bigEndian)
  // A short sits at the near end of its four byte field, so the two bytes after it stay zero.
  segment.putShort(ENTRY_OFFSET + 8, orientation, bigEndian)

  return segment
}

private fun baselineJpeg(): ByteArray = Base64.decode(BASELINE_JPEG.filterNot(Char::isWhitespace))

private fun ByteArray.putInt(
  offset: Int,
  value: Int,
  bigEndian: Boolean = false,
) = put(offset, value, 4, bigEndian)

private fun ByteArray.putShort(
  offset: Int,
  value: Int,
  bigEndian: Boolean = false,
) = put(offset, value, 2, bigEndian)

private fun ByteArray.put(
  offset: Int,
  value: Int,
  width: Int,
  bigEndian: Boolean,
) {
  for (byte in 0 until width) {
    val shift = if (bigEndian) width - 1 - byte else byte
    this[offset + byte] = (value shr (shift * BITS_PER_BYTE)).toByte()
  }
}

private const val FILE_HEADER_BYTES = 14
private const val INFO_HEADER_BYTES = 40
private const val HEADER_BYTES = FILE_HEADER_BYTES + INFO_HEADER_BYTES
private const val BITMAP_DATA_OFFSET = 10
private const val BYTES_PER_PIXEL = 3
private const val ROW_ALIGNMENT = 4
private const val BITS_PER_BYTE = 8
private const val GREY: Byte = 0x7F

private const val SOI_BYTES = 2

private val EXIF_IDENTIFIER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

private const val APP1_MARKER = 0xFFE1
private const val SEGMENT_BYTES = 36
private const val MARKER_BYTES = 2
private const val IDENTIFIER_OFFSET = 4
private const val TIFF_OFFSET = 10
private const val DIRECTORY_OFFSET = 18
private const val ENTRY_OFFSET = 20

private const val MOTOROLA = 0x4D4D
private const val INTEL = 0x4949
private const val TIFF_MAGIC = 42
private const val TIFF_HEADER_BYTES = 8
private const val ORIENTATION_TAG = 0x0112
private const val TYPE_SHORT = 3

/**
 * A baseline JPEG of [IMAGE_PROBE_SIZE], carrying no EXIF block of its own, so the only orientation
 * a target can read out of it is the one [rotatedImageProbeBytes] splices in.
 */
private val BASELINE_JPEG =
  """
  /9j/2wBDAFA3PEY8MlBGQUZaVVBfeMiCeG5uePWvuZHI////////////////////////////////////////////////////
  2wBDAVVaWnhpeOuCguv/////////////////////////////////////////////////////////////////////////wAAR
  CAAoAEADASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAAAP/EABYQAQEBAAAAAAAAAAAAAAAAAAABMf/EABUBAQEAAAAA
  AAAAAAAAAAAAAAAB/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8ATCYTCYKTCYTCYBMJhMJgEwmEwmATCYTC
  YBMJhMJgEwmEwmATCYTCYBMJgATCYAEwmABMJgA//9k=
  """.trimIndent()
