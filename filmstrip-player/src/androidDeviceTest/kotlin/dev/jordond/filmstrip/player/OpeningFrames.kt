package dev.jordond.filmstrip.player

import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE

/**
 * The software H.264 encoder the encoder bounds in these suites were measured on.
 */
internal const val MEASURED_ENCODER = "c2.android.avc.encoder"

/**
 * The video encoder an export of the fixture runs on, the one media3's default encoder factory makes for it.
 *
 * Every export asks that factory for its video encoder, so asking it here for the fixture's H.264 format at
 * [PREVIEW_PARITY_EXPORT_BITRATE] makes the same one. It is released as soon as its name is read.
 */
internal val exportVideoEncoder: String by lazy {
  val format =
    Format
      .Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H264)
      .setWidth(FIXTURE_FRAME.width)
      .setHeight(FIXTURE_FRAME.height)
      .setFrameRate(FIXTURE_FRAME_RATE.toFloat())
      .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
      .build()
  val settings =
    VideoEncoderSettings
      .Builder()
      .setBitrate(PREVIEW_PARITY_EXPORT_BITRATE.bitsPerSecond.toInt())
      .build()
  val encoder =
    DefaultEncoderFactory
      .Builder(contractContext())
      .setRequestedVideoEncoderSettings(settings)
      .build()
      .createForVideoEncoding(format, null)
  try {
    encoder.name
  } finally {
    encoder.release()
  }
}

/**
 * Whether an export runs on [MEASURED_ENCODER], so the bounds measured on it apply.
 */
internal val onMeasuredEncoder: Boolean get() = exportVideoEncoder == MEASURED_ENCODER

/**
 * How many frames open a file that [MEASURED_ENCODER] writes further off the preview than the rest.
 *
 * Frames 0 to 6 of any file sit off under every setting and bitrate media3 offers, measured three runs each on API 30
 * and 36, and frame 7 is settled. That follows the frame's place in the file, at any speed. Any other encoder's opening
 * isn't measured, so every frame it writes is held as an opening one.
 */
internal val openingFrames: Long get() = if (onMeasuredEncoder) MEASURED_OPENING_FRAMES else Long.MAX_VALUE

/**
 * The SSIM an export's frame at [slot] holds to on luma against the preview.
 *
 * A slot past [openingFrames] holds [pastOpening]. A slot inside them holds the opening floor, or [pastOpening] if
 * that is lower.
 */
internal fun encodedMinSsimAt(
  slot: Long,
  pastOpening: Double,
): Double = if (slot < openingFrames) minOf(OPENING_MIN_SSIM, pastOpening) else pastOpening

/**
 * The frames [MEASURED_ENCODER] opens a file with, the ones the opening bounds were measured over.
 */
internal const val MEASURED_OPENING_FRAMES: Long = 7L

// c2.android.avc.encoder's limit over its opening frames, 45 to 49 dB on luma: frame 0 of a moving source reads 0.984
// against its own preview on API 36, the same bytes at speed 1 and at 32, where a wrong frame reads 0.844 to 0.857.
// Another encoder holds every frame to it, 0.093 over the most a wrong frame reads.
private const val OPENING_MIN_SSIM = 0.95
