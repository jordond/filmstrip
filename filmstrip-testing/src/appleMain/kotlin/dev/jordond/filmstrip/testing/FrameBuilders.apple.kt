package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.media.PlatformImage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo

@OptIn(ExperimentalForeignApi::class)
public actual fun testPlatformImage(
  widthPx: Int,
  heightPx: Int,
): PlatformImage {
  val space = CGColorSpaceCreateDeviceRGB()
  try {
    // A context that owns its buffer starts zeroed, and skipping the alpha byte makes that opaque black.
    val context =
      checkNotNull(
        CGBitmapContextCreate(
          data = null,
          width = widthPx.toULong(),
          height = heightPx.toULong(),
          bitsPerComponent = 8uL,
          bytesPerRow = 0uL,
          space = space,
          bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        ),
      ) { "Could not make a ${widthPx}x$heightPx bitmap." }
    return try {
      PlatformImage(checkNotNull(CGBitmapContextCreateImage(context)))
    } finally {
      CGContextRelease(context)
    }
  } finally {
    CGColorSpaceRelease(space)
  }
}
