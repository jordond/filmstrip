package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.test.ycbcrToRgb
import java.io.File

/**
 * One frame an export wrote, as the codes its encoder stored and the ITU-T H.273 matrix and range its file records.
 *
 * @property codes The frame's luma, Cb and Cr codes.
 * @property matrix The matrix code the file records.
 * @property fullRange Whether the file records full range.
 */
internal class Written(
  val codes: FrameCodes,
  val matrix: Int,
  val fullRange: Boolean,
) {
  /**
   * The color at the fractions [fx] and [fy] of the frame, turned into RGB through [matrix] and [fullRange].
   */
  fun rgbAt(
    fx: Float,
    fy: Float,
  ): Triple<Int, Int, Int> {
    val (y, cb, cr) = codes.codesAt(fx, fy)
    // A video tag's matrix reads through its Kr and Kb alone, so primaries and transfer stay unspecified.
    val (red, green, blue) = ycbcrToRgb(y, cb, cr, matrix, UNSPECIFIED, UNSPECIFIED, fullRange)
    return Triple(red, green, blue)
  }
}

/**
 * The ITU-T H.273 matrix and full range flag [video]'s video track records.
 */
internal fun colorTagOf(video: File): Pair<Int, Boolean> {
  val extractor = MediaExtractor()
  try {
    extractor.setDataSource(video.path)
    val format =
      (0 until extractor.trackCount)
        .map(extractor::getTrackFormat)
        .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
    val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
    val matrix = checkNotNull(MATRIX_OF_STANDARD[standard]) { "$video records color standard $standard" }
    return matrix to (format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL)
  } finally {
    extractor.release()
  }
}

/**
 * The H.273 matrix code each `MediaFormat` color standard reads through.
 */
internal val MATRIX_OF_STANDARD =
  mapOf(
    MediaFormat.COLOR_STANDARD_BT709 to 1,
    MediaFormat.COLOR_STANDARD_BT601_PAL to 5,
    MediaFormat.COLOR_STANDARD_BT601_NTSC to 6,
    MediaFormat.COLOR_STANDARD_BT2020 to 9,
  )

private const val UNSPECIFIED = 2
