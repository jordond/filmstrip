package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * The Apple form, wrapping a `CGImage`.
 *
 * Swift callers read pixels through [toNSData] and take a Core Image handle through [toCIImage].
 * The `CGImage` itself is not exposed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public actual class PlatformImage
  @InternalFilmstripApi
  constructor(
    private var image: CGImageRef?,
  ) : AutoCloseable {
    public actual val widthPx: Int
      get() = image?.let { CGImageGetWidth(it).toInt() } ?: 0

    public actual val heightPx: Int
      get() = image?.let { CGImageGetHeight(it).toInt() } ?: 0

    /**
     * The frame as a `CIImage`, for further Core Image work without a round trip through bytes.
     *
     * @return the image, or null once [close] has been called.
     */
    public fun toCIImage(): CIImage? = image?.let { CIImage.imageWithCGImage(it) }

    /**
     * The pixels as tightly packed RGBA_8888 in an `NSData`, which Swift sees as `Data`.
     *
     * The path a Swift caller should take, since it reads at native speed rather than one bridged
     * call per byte.
     *
     * @return the pixel data, or an empty `NSData` once [close] has been called.
     */
    public fun toNSData(): NSData {
      val bytes = toRgba8888()
      if (bytes.isEmpty()) return NSData()
      return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
      }
    }

    public actual fun toRgba8888(): ByteArray {
      val source = image ?: return ByteArray(0)
      val width = CGImageGetWidth(source).toInt()
      val height = CGImageGetHeight(source).toInt()
      val bytes = ByteArray(width * height * BYTES_PER_PIXEL)

      val deviceRgb = CGColorSpaceCreateDeviceRGB()
      bytes.usePinned { pinned ->
        val context =
          CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = BITS_PER_COMPONENT,
            bytesPerRow = (width * BYTES_PER_PIXEL).toULong(),
            space = deviceRgb,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
          )
        CGContextDrawImage(
          context,
          CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
          source,
        )
        CGContextRelease(context)
      }
      CGColorSpaceRelease(deviceRgb)
      return bytes
    }

    actual override fun close() {
      image?.let(::CGImageRelease)
      image = null
    }
  }

private const val BYTES_PER_PIXEL = 4
private const val BITS_PER_COMPONENT = 8uL
