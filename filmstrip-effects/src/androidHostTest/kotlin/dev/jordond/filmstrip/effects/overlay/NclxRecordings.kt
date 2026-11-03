package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.test.ColorPictures

// An AVIF whose codestream states no color, recording [matrix] and [fullRange] in its nclx.
internal fun avifRecording(
  matrix: Int,
  fullRange: Boolean,
): ByteArray = recording(ColorPictures.NCLX_DISPLAY_P3.bytes(), matrix, fullRange)

// A HEIC whose codestream signals nothing, recording [matrix] and [fullRange] in its nclx.
internal fun heicRecording(
  matrix: Int,
  fullRange: Boolean,
): ByteArray = recording(ColorPictures.HEIC_NCLX_BT709_FULL.bytes(), matrix, fullRange)

// [file] with its nclx recording BT.709 primaries, the sRGB transfer, [matrix] and [fullRange].
internal fun recording(
  file: ByteArray,
  matrix: Int,
  fullRange: Boolean,
): ByteArray {
  val nclx = String(file, Charsets.ISO_8859_1).indexOf("nclx") + 4
  val fields =
    byteArrayOf(0, BT709.toByte(), 0, SRGB_TRANSFER.toByte(), 0, matrix.toByte(), if (fullRange) FULL_RANGE else 0)
  return file.also { fields.copyInto(it, nclx) }
}

private const val BT709 = 1
private const val SRGB_TRANSFER = 13
private const val FULL_RANGE = 0x80.toByte()

// How core reads a picture recording [matrix] beside BT.709 primaries and the sRGB transfer, as a recording does.
internal fun reading(matrix: Int): YcbcrReading = ycbcrReading(matrix, BT709, SRGB_TRANSFER)
