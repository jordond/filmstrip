package dev.jordond.filmstrip.test

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.ycbcrReading
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Decodes the frame of [video] nearest [at] as an ARGB_8888 bitmap.
 *
 * A PQ or HLG video is read as [hdrSignalFrameAt] reads it. `MediaMetadataRetriever` tone maps an HDR frame however
 * the device likes, and a Galaxy Z Fold7 on API 37 hands it back as RGBA_1010102 whatever config is asked for.
 *
 * Asked without bitmap params, `MediaMetadataRetriever` handed back RGB_565 on API 30, which moved a mid grey of 128
 * to 132, 130, 132. `MediaMetadataRetriever.BitmapParams` arrived in API 30, so an older device reads the sample
 * nearest [at] through [eightBitFrameThroughPlayerAt] instead, asked for at that sample's own time.
 *
 * @throws IllegalArgumentException When [video] holds no frame at [at].
 * @throws IllegalStateException When the frame comes back in any other config.
 */
@InternalFilmstripApi
public fun eightBitFrameAt(
  video: File,
  at: Duration,
): Bitmap {
  if (isHdr(video)) return hdrSignalFrameAt(video, at)
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    val nearest =
      requireNotNull(videoSampleTimesOf(video).minByOrNull { (it - at).absoluteValue }) { "no frame in $video" }
    return eightBitFrameThroughPlayerAt(video, nearest)
  }
  val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(video.path)
    val frame =
      requireNotNull(retriever.getFrameAtTime(at.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST, params)) {
        "no frame at $at in $video"
      }
    check(frame.config == Bitmap.Config.ARGB_8888) { "the frame at $at came back as ${frame.config}, not ARGB_8888" }
    frame
  } finally {
    retriever.release()
  }
}

/**
 * Decodes the frame of the PQ or HLG [video] nearest [at] as an ARGB_8888 bitmap holding its signal.
 *
 * Each pixel is the red, green and blue the decoder's ten-bit samples give through the BT.2020 matrix at the range the
 * file records, rounded to eight bits. Nothing is tone mapped, so a channel is the PQ or HLG signal and not a light or
 * an sRGB level, and it reads the same on every device.
 */
@InternalFilmstripApi
public fun hdrSignalFrameAt(
  video: File,
  at: Duration,
): Bitmap {
  val codes = tenBitFrameCodesAt(video, at)
  val fullRange = videoFormatOf(video).rangeIsFull()
  val evaluator = ycbcrReading(BT2020_MATRIX, UNSPECIFIED, UNSPECIFIED).evaluator(TEN_BITS, fullRange)
  val width = codes.size.width
  val height = codes.size.height
  val pixels =
    IntArray(width * height) { index ->
      val x = index % width
      val y = index / width
      // A frame of odd size has no chroma sample past its last whole pair.
      val column = (x / 2).coerceAtMost(width / 2 - 1)
      val row = (y / 2).coerceAtMost(height / 2 - 1)
      val rgb = evaluator.rgb(codes.lumaAt(x, y), codes.cbAt(column, row), codes.crAt(column, row))
      Color.rgb(rgb[0].toCode(), rgb[1].toCode(), rgb[2].toCode())
    }
  return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun isHdr(video: File): Boolean {
  val format = videoFormatOf(video)
  if (!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) return false
  val transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
  return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
}

private fun MediaFormat.rangeIsFull(): Boolean =
  containsKey(MediaFormat.KEY_COLOR_RANGE) && getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL

private fun videoFormatOf(video: File): MediaFormat {
  val extractor = MediaExtractor()
  return try {
    extractor.setDataSource(video.path)
    (0 until extractor.trackCount)
      .map { extractor.getTrackFormat(it) }
      .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
  } finally {
    extractor.release()
  }
}

private fun Double.toCode(): Int = (coerceIn(0.0, 1.0) * MAX_CODE).roundToInt()

private const val BT2020_MATRIX = 9
private const val UNSPECIFIED = 2
private const val TEN_BITS = 10
private const val MAX_CODE = 255

/**
 * Decodes the frame of [video] a player shows at [at] as an ARGB_8888 bitmap, the last sample presented at or before
 * it.
 *
 * The sample times are read off the container by media3's `MediaExtractorCompat` and that sample is decoded at its own
 * time, so a file that writes no sample on a slot reads as the one it holds over that slot rather than the nearest one
 * either side. [eightBitFrameAt] covers the API level and the config this takes.
 *
 * @throws IllegalArgumentException When [video] holds no video sample at or before [at].
 */
@InternalFilmstripApi
public fun eightBitFrameCovering(
  video: File,
  at: Duration,
): Bitmap = eightBitFrameAt(video, coveringSampleTimeOf(video, at))

/**
 * The time of the last video sample in [video] presented at or before [at], which is the frame a player shows there.
 *
 * @throws IllegalArgumentException When [video] holds no video sample at or before [at].
 */
internal fun coveringSampleTimeOf(
  video: File,
  at: Duration,
): Duration =
  requireNotNull(videoSampleTimesOf(video).lastOrNull { it <= at }) { "no video sample at or before $at in $video" }

/**
 * The time of every video sample in [video], read off its container by media3's `MediaExtractorCompat`, in presentation
 * order rather than the order a file with reordered frames stores them in.
 *
 * @throws IllegalArgumentException When [video] holds no video track.
 */
@InternalFilmstripApi
public fun videoSampleTimesOf(video: File): List<Duration> {
  val extractor = MediaExtractorCompat()
  return try {
    extractor.setDataSource(Uri.fromFile(video), 0L)
    val track =
      (0 until extractor.trackCount).firstOrNull {
        extractor
          .getTrackFormat(it)
          .getString(MediaFormat.KEY_MIME)
          .orEmpty()
          .startsWith("video/")
      }
    requireNotNull(track) { "$video holds no video track" }
    extractor.selectTrack(track)
    val times = mutableListOf<Duration>()
    while (extractor.sampleTrackIndex >= 0) {
      times += extractor.sampleTime.microseconds
      extractor.advance()
    }
    times.sorted()
  } finally {
    extractor.release()
  }
}
