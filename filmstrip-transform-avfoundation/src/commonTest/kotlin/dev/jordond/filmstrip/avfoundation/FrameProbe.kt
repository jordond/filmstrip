package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.FrameProbe.Companion.PATCH
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoComposition
import platform.AVFoundation.segments
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetDuration
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeSubtract
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.time.Duration

/**
 * One decoded video frame, held as RGBA bytes.
 *
 * Decoding costs a whole `AVAssetImageGenerator` pass, and a test that measures a region reads
 * dozens of points out of one frame, so the frame is drawn into a buffer of a known format once and
 * every read comes out of that. `AVAssetImageGenerator` hands back whatever pixel format the file
 * happens to carry, which is what the redraw settles.
 *
 * Coordinates are fractions of the frame from the top-left, the convention filmstrip's own geometry
 * types use.
 */
internal class FrameProbe(
  val width: Int,
  val height: Int,
  private val pixels: ByteArray,
) {
  /**
   * The color of the single pixel at ([xFraction], [yFraction]).
   */
  fun at(
    xFraction: Float,
    yFraction: Float,
  ): Triple<Int, Int, Int> {
    val x = (width * xFraction).toInt().coerceIn(0, width - 1)
    val y = (height * yFraction).toInt().coerceIn(0, height - 1)
    val offset = (y * width + x) * CHANNELS
    return Triple(
      pixels[offset].toInt() and BYTE_MASK,
      pixels[offset + 1].toInt() and BYTE_MASK,
      pixels[offset + 2].toInt() and BYTE_MASK,
    )
  }

  /**
   * The average color of a [PATCH] square centred on ([xFraction], [yFraction]).
   *
   * A single pixel lands wherever chroma subsampling left it, so anything measuring a region reads
   * an average rather than a sample. Each channel rounds to the nearest code.
   */
  fun average(
    xFraction: Float,
    yFraction: Float,
  ): Triple<Int, Int, Int> {
    val left = ((width * xFraction).toInt() - PATCH / 2).coerceIn(0, width - PATCH)
    val top = ((height * yFraction).toInt() - PATCH / 2).coerceIn(0, height - PATCH)
    var red = 0
    var green = 0
    var blue = 0
    for (y in top until top + PATCH) {
      for (x in left until left + PATCH) {
        val offset = (y * width + x) * CHANNELS
        red += pixels[offset].toInt() and BYTE_MASK
        green += pixels[offset + 1].toInt() and BYTE_MASK
        blue += pixels[offset + 2].toInt() and BYTE_MASK
      }
    }
    val sampled = PATCH * PATCH

    fun rounded(sum: Int): Int = (sum + sampled / 2) / sampled
    return Triple(rounded(red), rounded(green), rounded(blue))
  }

  companion object {
    const val PATCH = 4
    private const val CHANNELS = 4
    private const val BYTE_MASK = 0xFF
  }
}

/**
 * Decodes the video frame of the file at [path] covering [at].
 *
 * Both tolerances are pinned, so what comes back is the frame at the time that was asked for rather
 * than the nearest sync sample, which is what lets a caller sample the middle of a clip's span.
 */
internal fun frameOf(
  path: String,
  at: Duration = Duration.ZERO,
): FrameProbe = frameOf(AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null), null, at)

/**
 * Renders the frame of [asset] covering [at] through [videoComposition], the way the thumbnail source asks for one.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal fun frameOf(
  asset: AVAsset,
  videoComposition: AVVideoComposition?,
  at: Duration,
): FrameProbe {
  val generator = AVAssetImageGenerator(asset = asset)
  generator.videoComposition = videoComposition
  generator.appliesPreferredTrackTransform = true
  generator.requestedTimeToleranceBefore = CMTimeMake(value = 0, timescale = MEDIA_TIMESCALE)
  generator.requestedTimeToleranceAfter = CMTimeMake(value = 0, timescale = MEDIA_TIMESCALE)
  val frame =
    generator.copyCGImageAtTime(at.toCMTime(), actualTime = null, error = null)
      ?: error("could not read a frame at $at")

  try {
    val width = CGImageGetWidth(frame).toInt()
    val height = CGImageGetHeight(frame).toInt()
    val pixels = ByteArray(width * height * BYTES_PER_PIXEL)

    val colorSpace = CGColorSpaceCreateDeviceRGB()
    try {
      pixels.usePinned { pinned ->
        val context =
          CGBitmapContextCreate(
            pinned.addressOf(0),
            width.toULong(),
            height.toULong(),
            BITS_PER_CHANNEL.toULong(),
            (width * BYTES_PER_PIXEL).toULong(),
            colorSpace,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
          ) ?: error("could not create a bitmap context")
        try {
          CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), frame)
        } finally {
          CGContextRelease(context)
        }
      }
    } finally {
      CGColorSpaceRelease(colorSpace)
    }

    return FrameProbe(width, height, pixels)
  } finally {
    CGImageRelease(frame)
  }
}

/**
 * When each video sample of the file at [path] is presented, earliest first.
 *
 * Read off the samples as they sit in the file rather than decoded, so what comes back is where the writer put each
 * frame, which a decoded picture at a time cannot say.
 */
internal fun sampleTimesOf(path: String): List<Duration> = videoSamplesOf(path).map { it.at }

/**
 * One video sample of a file, as when it is presented and how long it is shown for.
 */
internal data class VideoSample(
  val at: Duration,
  val length: Duration,
)

/**
 * Every video sample of the file at [path], earliest first.
 *
 * The reader hands samples over in decode order and on the track's own media timeline, so each time is moved through
 * the track's opening edit and the samples are sorted by when they are shown.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun videoSamplesOf(path: String): List<VideoSample> {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track =
    asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
      ?: error("$path carries no video track")
  val output = AVAssetReaderTrackOutput(track = track, outputSettings = null)
  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for $path")
  reader.addOutput(output)
  if (!reader.startReading()) error("the video reader refused to start: ${reader.error?.localizedDescription}")

  // An encoder that reorders frames stamps every sample later than it is shown, and opens the track with an edit
  // that moves them back.
  // Moved in the track's own time rather than after rounding to a duration, so a sample reads back exactly.
  val shift =
    track.segments.filterIsInstance<AVAssetTrackSegment>().firstOrNull { !it.empty }?.timeMapping?.useContents {
      CMTimeSubtract(target.start.readValue(), source.start.readValue())
    } ?: Duration.ZERO.toCMTime()

  val samples = mutableListOf<VideoSample>()
  try {
    while (true) {
      val buffer = output.copyNextSampleBuffer() ?: break
      try {
        if (CMSampleBufferGetNumSamples(buffer) > 0) {
          samples +=
            VideoSample(
              at = CMTimeAdd(CMSampleBufferGetPresentationTimeStamp(buffer), shift).toDuration(),
              length = CMSampleBufferGetDuration(buffer).toDuration(),
            )
        }
      } finally {
        CFRelease(buffer)
      }
    }
  } finally {
    reader.cancelReading()
  }
  return samples.sortedBy { it.at }
}

/**
 * The color of the single pixel at ([xFraction], [yFraction]) of the first frame of [path].
 */
internal fun samplePixel(
  path: String,
  xFraction: Float,
  yFraction: Float,
): Triple<Int, Int, Int> = frameOf(path).at(xFraction, yFraction)

/**
 * The sum of each channel's difference between two colors.
 */
internal fun distance(
  a: Triple<Int, Int, Int>,
  b: Triple<Int, Int, Int>,
): Int = abs(a.first - b.first) + abs(a.second - b.second) + abs(a.third - b.third)

/**
 * The sum of a color's three channels.
 */
internal fun luminance(color: Triple<Int, Int, Int>): Int = color.first + color.second + color.third

/**
 * Packs this RGB color as opaque ARGB, the shape a solid fill takes.
 */
internal fun Triple<Int, Int, Int>.toArgb(): Int =
  (ALPHA shl ALPHA_SHIFT) or (first shl RED_SHIFT) or (second shl GREEN_SHIFT) or third

/**
 * Asserts [actual] is within [COLOR_TOLERANCE] of [expected], the slack a real hardware encode
 * leaves even on a flat color.
 */
internal fun assertClose(
  actual: Triple<Int, Int, Int>,
  expected: Triple<Int, Int, Int>,
  label: String,
) {
  withClue("expected $label to be close to $expected, got $actual") {
    (distance(actual, expected) <= COLOR_TOLERANCE).shouldBeTrue()
  }
}

internal const val COLOR_TOLERANCE = 24

private const val BITS_PER_CHANNEL = 8
private const val BYTES_PER_PIXEL = 4
private const val ALPHA = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
