package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetImageGenerator
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import kotlin.time.Duration

/**
 * The frame [generator] gives at [time], drawn into plain BGRA with no colour matching on the way.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal fun generatedCodeFrame(
  generator: AVAssetImageGenerator,
  time: Duration,
): CodeFrame {
  val image =
    generator.copyCGImageAtTime(time.toCMTime(), actualTime = null, error = null) ?: error("no frame at $time")
  try {
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    val rowBytes = width * BGRA_BYTES
    val bgra = ByteArray(rowBytes * height)
    val space = CGImageGetColorSpace(image) ?: error("the frame at $time has no colour space")
    bgra.usePinned { pinned ->
      val context =
        CGBitmapContextCreate(
          pinned.addressOf(0),
          width.toULong(),
          height.toULong(),
          BITS_PER_CHANNEL,
          rowBytes.toULong(),
          space,
          CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little,
        ) ?: error("no bitmap context for the frame at $time")
      try {
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
      } finally {
        CGContextRelease(context)
      }
    }
    return CodeFrame(width, height, bgra, rowBytes)
  } finally {
    CGImageRelease(image)
  }
}

private const val BGRA_BYTES = 4
private const val BITS_PER_CHANNEL = 8uL
