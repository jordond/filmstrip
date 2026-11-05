package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.test.ycbcrToRgb
import java.io.File

/**
 * One frame an export wrote, as the codes its encoder stored and the ITU-T H.273 matrix and range its file records.
 *
 * media3 may encode a portrait frame turned on its side and record the quarter turn in the container, so a point is
 * read where the frame is shown, turned back through [rotationDegrees] to where it is stored.
 *
 * @property codes The frame's luma, Cb and Cr codes, as stored.
 * @property matrix The matrix code the file records.
 * @property fullRange Whether the file records full range.
 * @property rotationDegrees How far clockwise the container turns the stored frame to show it.
 */
internal class Written(
  val codes: FrameCodes,
  val matrix: Int,
  val fullRange: Boolean,
  val rotationDegrees: Int = 0,
) {
  /**
   * The color at the fractions [fx] and [fy] of the frame as shown, turned into RGB through [matrix] and [fullRange].
   */
  fun rgbAt(
    fx: Float,
    fy: Float,
  ): Triple<Int, Int, Int> {
    val (storedX, storedY) = storedPointOf(fx, fy)
    val (y, cb, cr) = codes.codesAt(storedX, storedY)
    // A video tag's matrix reads through its Kr and Kb alone, so primaries and transfer stay unspecified.
    val (red, green, blue) = ycbcrToRgb(y, cb, cr, matrix, UNSPECIFIED, UNSPECIFIED, fullRange)
    return Triple(red, green, blue)
  }

  // Turning the stored frame a quarter clockwise takes its point (x, y) to (1 - y, x) of the shown one.
  private fun storedPointOf(
    fx: Float,
    fy: Float,
  ): Pair<Float, Float> =
    when (rotationDegrees) {
      0 -> fx to fy
      QUARTER_TURN -> fy to 1f - fx
      HALF_TURN -> 1f - fx to 1f - fy
      THREE_QUARTER_TURN -> 1f - fy to fx
      else -> error("a container turns a frame by a quarter at a time, not $rotationDegrees degrees")
    }
}

/**
 * The ITU-T H.273 matrix and full range flag [video]'s video track records.
 */
internal fun colorTagOf(video: File): Pair<Int, Boolean> {
  val format = videoFormatOf(video)
  val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
  val matrix = checkNotNull(MATRIX_OF_STANDARD[standard]) { "$video records color standard $standard" }
  return matrix to (format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL)
}

/**
 * How far clockwise [video]'s container turns its stored frames to show them.
 */
internal fun rotationOf(video: File): Int {
  val format = videoFormatOf(video)
  return if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
}

private fun videoFormatOf(video: File): MediaFormat {
  val extractor = MediaExtractor()
  try {
    extractor.setDataSource(video.path)
    return (0 until extractor.trackCount)
      .map(extractor::getTrackFormat)
      .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
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
private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270
