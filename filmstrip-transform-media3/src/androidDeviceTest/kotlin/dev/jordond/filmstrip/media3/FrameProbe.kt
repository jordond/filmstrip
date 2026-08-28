package dev.jordond.filmstrip.media3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration

/**
 * A rectangle of the frame to measure, as fractions.
 */
internal class Region(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

/**
 * Decodes the frame of [video] nearest [at].
 */
internal fun frameOf(
  video: File,
  at: Duration,
): Bitmap {
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(video.path)
    requireNotNull(retriever.getFrameAtTime(at.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST)) {
      "no frame at $at in $video"
    }
  } finally {
    retriever.release()
  }
}

/**
 * An opaque orange square, written into [context]'s cache the first time it is asked for.
 *
 * Orange is the one channel ordering no bar of the generated test pattern shares, red well above
 * green well above blue, where a red or a green badge would collide with one.
 */
internal fun badgeFile(context: Context): File {
  val file = File(context.cacheDir, "badge.png")
  if (file.exists()) return file
  val bitmap = Bitmap.createBitmap(BADGE_PX, BADGE_PX, Bitmap.Config.ARGB_8888)
  bitmap.eraseColor(BADGE_COLOR)
  file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
  return file
}

/**
 * How much more of [region] reads as the badge colour here than in [plain], as a fraction of the
 * cells sampled.
 *
 * Asserting on the increase rather than an absolute count is what makes the measurement hold on a
 * frame nobody controls: whatever the pattern was already drawing in that region cancels out.
 */
internal fun Bitmap.gainedOver(
  plain: Bitmap,
  region: Region,
): Float = badgeFraction(region) - plain.badgeFraction(region)

/**
 * How much of [region] reads as the badge colour, as a fraction of the cells sampled.
 */
internal fun Bitmap.badgeFraction(region: Region): Float {
  var hits = 0
  for (row in 0 until CELLS) {
    for (column in 0 until CELLS) {
      val x = region.left + (region.right - region.left) * (column + 0.5f) / CELLS
      val y = region.top + (region.bottom - region.top) * (row + 0.5f) / CELLS
      if (isBadgeAt(x, y)) hits++
    }
  }
  return hits.toFloat() / (CELLS * CELLS)
}

/**
 * How much of one full-width row reads as the badge colour, as a fraction of the width.
 */
internal fun Bitmap.badgeSpan(fy: Float): Float {
  var hits = 0
  for (column in 0 until SPAN_SAMPLES) {
    if (isBadgeAt((column + 0.5f) / SPAN_SAMPLES, fy)) hits++
  }
  return hits.toFloat() / SPAN_SAMPLES
}

/**
 * Whether the badge covers ([fx], [fy]).
 *
 * Averaged over a patch, because a single pixel lands wherever chroma subsampling left it, and read
 * as gaps between channels, not as values, since encoding moves all three together but leaves their
 * ordering alone.
 */
internal fun Bitmap.isBadgeAt(
  fx: Float,
  fy: Float,
): Boolean {
  val (red, green, blue) = averageAt(fx, fy)
  return red - green in CHANNEL_GAP && green - blue in CHANNEL_GAP && red - blue > CHANNEL_SPAN
}

/**
 * The average colour of a [PATCH] square whose top-left corner sits at ([fx], [fy]).
 */
internal fun Bitmap.averageAt(
  fx: Float,
  fy: Float,
): Triple<Int, Int, Int> {
  val left = ((width - PATCH) * fx).toInt().coerceIn(0, width - PATCH)
  val top = ((height - PATCH) * fy).toInt().coerceIn(0, height - PATCH)
  var red = 0
  var green = 0
  var blue = 0
  for (x in left until left + PATCH) {
    for (y in top until top + PATCH) {
      val pixel = getPixel(x, y)
      red += Color.red(pixel)
      green += Color.green(pixel)
      blue += Color.blue(pixel)
    }
  }
  val sampled = PATCH * PATCH
  return Triple(red / sampled, green / sampled, blue / sampled)
}

/**
 * The sum of each channel's difference between two colours.
 */
internal fun distance(
  a: Triple<Int, Int, Int>,
  b: Triple<Int, Int, Int>,
): Int = abs(a.first - b.first) + abs(a.second - b.second) + abs(a.third - b.third)

/**
 * The sum of a colour's three channels.
 */
internal fun luminance(color: Triple<Int, Int, Int>): Int = color.first + color.second + color.third

// Orange: 255, 140, 0.
internal const val BADGE_COLOR = 0xFFFF8C00.toInt()
internal const val BADGE_PX = 64
internal const val BADGE_SCALE = 0.3f

// The badge is opaque and covers its region, so a real hit turns most cells orange. A region it
// never touched moves by a cell or two as the encoder rounds, and no more.
internal const val COVERED = 0.6f
internal const val UNTOUCHED = 0.1f

internal val CHANNEL_GAP = 40..190
internal const val CHANNEL_SPAN = 140

internal const val CELLS = 6
internal const val PATCH = 4
internal const val SPAN_SAMPLES = 40
private const val PNG_QUALITY = 100

/**
 * Whether this device has a decoder for the ten-bit HEVC the HDR fixtures are written in.
 *
 * Encoding and decoding are separate questions. Tone mapping needs only the decoder, which is
 * exactly the case a device with no HDR encoder is still expected to serve.
 *
 * The emulator carries `OMX.google.hevc.decoder`, which advertises eight-bit HEVC only and throws
 * when media3 hands it a Main10 stream because it is the sole HEVC decoder on the image. Asking
 * for the profile rather than for the codec is what separates the two.
 */
internal fun decodesTenBitHevc(): Boolean =
  MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
    !codec.isEncoder &&
      codec.supportedTypes.any { it.equals(HEVC_MIME, ignoreCase = true) } &&
      codec.getCapabilitiesForType(HEVC_MIME).profileLevels.any { it.profile in TEN_BIT_HEVC_PROFILES }
  }

private const val HEVC_MIME = "video/hevc"

private val TEN_BIT_HEVC_PROFILES =
  setOf(
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
  )
