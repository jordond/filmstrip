package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetImageGenerator
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
import kotlin.time.Duration

/**
 * The frame this generator renders at [time], drawn into RGBA bytes.
 *
 * The bytes are premultiplied, in the device RGB space.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal fun AVAssetImageGenerator.frameAt(time: Duration): TestFrame {
  val image =
    copyCGImageAtTime(time.toCMTime(), actualTime = null, error = null)
      ?: error("no frame came back at $time")

  try {
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    val pixels = ByteArray(width * height * CHANNELS)
    val colorSpace = CGColorSpaceCreateDeviceRGB()

    try {
      pixels.usePinned { pinned ->
        val context =
          CGBitmapContextCreate(
            pinned.addressOf(0),
            width.toULong(),
            height.toULong(),
            BITS_PER_CHANNEL.toULong(),
            (width * CHANNELS).toULong(),
            colorSpace,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
          ) ?: error("could not create a bitmap context")
        try {
          CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
        } finally {
          CGContextRelease(context)
        }
      }
    } finally {
      CGColorSpaceRelease(colorSpace)
    }

    return TestFrame(pixels, Size(width, height))
  } finally {
    CGImageRelease(image)
  }
}

private const val CHANNELS = 4
private const val BITS_PER_CHANNEL = 8
