package dev.jordond.filmstrip.avfoundation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
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
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.test.assertTrue

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
   * The colour of the single pixel at ([xFraction], [yFraction]).
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
   * The average colour of a [PATCH] square centred on ([xFraction], [yFraction]).
   *
   * A single pixel lands wherever chroma subsampling left it, so anything measuring a region reads
   * an average rather than a sample.
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
    return Triple(red / sampled, green / sampled, blue / sampled)
  }

  companion object {
    const val PATCH = 4
    private const val CHANNELS = 4
    private const val BYTE_MASK = 0xFF
  }
}

/**
 * Decodes the first video frame of the file at [path].
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal fun frameOf(path: String): FrameProbe {
  val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
  generator.appliesPreferredTrackTransform = true
  val frame =
    generator.copyCGImageAtTime(CMTimeMake(value = 0, timescale = TIMESCALE), actualTime = null, error = null)
      ?: error("could not read a frame from $path")

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
 * The colour of the single pixel at ([xFraction], [yFraction]) of the first frame of [path].
 */
internal fun samplePixel(
  path: String,
  xFraction: Float,
  yFraction: Float,
): Triple<Int, Int, Int> = frameOf(path).at(xFraction, yFraction)

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

/**
 * Packs this RGB colour as opaque ARGB, the shape a solid fill takes.
 */
internal fun Triple<Int, Int, Int>.toArgb(): Int =
  (ALPHA shl ALPHA_SHIFT) or (first shl RED_SHIFT) or (second shl GREEN_SHIFT) or third

/**
 * Asserts [actual] is within [COLOR_TOLERANCE] of [expected], the slack a real hardware encode
 * leaves even on a flat colour.
 */
internal fun assertClose(
  actual: Triple<Int, Int, Int>,
  expected: Triple<Int, Int, Int>,
  label: String,
) {
  assertTrue(
    distance(actual, expected) <= COLOR_TOLERANCE,
    "expected $label to be close to $expected, got $actual",
  )
}

internal const val COLOR_TOLERANCE = 24

private const val TIMESCALE = 600
private const val BITS_PER_CHANNEL = 8
private const val BYTES_PER_PIXEL = 4
private const val ALPHA = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
