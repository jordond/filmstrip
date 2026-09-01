package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.posix.memcpy
import kotlin.test.fail

/**
 * Writes a flat [color] photo of [size] to [path] and answers the path.
 *
 * Flat, so a frame drawn from the photo is told from a frame drawn from a generated video fixture
 * by its colour alone, wherever in the frame it is sampled.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writePhoto(
  path: String,
  size: Size,
  color: Triple<Int, Int, Int>,
): String {
  val space = CGColorSpaceCreateDeviceRGB() ?: fail("Core Graphics refused a device RGB colour space")
  val context =
    CGBitmapContextCreate(
      data = null,
      width = size.width.toULong(),
      height = size.height.toULong(),
      bitsPerComponent = BITS_PER_COMPONENT,
      bytesPerRow = 0uL,
      space = space,
      bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
    ) ?: fail("Core Graphics refused a ${size.width}x${size.height} bitmap context")

  CGContextSetRGBFillColor(
    c = context,
    red = color.first / FULL,
    green = color.second / FULL,
    blue = color.third / FULL,
    alpha = 1.0,
  )
  CGContextFillRect(context, CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()))

  val image = CGBitmapContextCreateImage(context)
  CGContextRelease(context)
  CGColorSpaceRelease(space)
  if (image == null) fail("Core Graphics drew no image for the photo fixture")

  val url: CFURLRef = CFBridgingRetain(NSURL.fileURLWithPath(path))?.reinterpret() ?: fail("no URL for $path")
  val uti: CFStringRef = CFBridgingRetain(PNG_UTI)?.reinterpret() ?: fail("no type identifier for $PNG_UTI")

  try {
    val destination =
      CGImageDestinationCreateWithURL(url, uti, 1u, null) ?: fail("ImageIO would not write a PNG to $path")
    try {
      CGImageDestinationAddImage(destination, image, null)
      if (!CGImageDestinationFinalize(destination)) fail("ImageIO would not finalize $path")
    } finally {
      CFRelease(destination)
    }
  } finally {
    CGImageRelease(image)
    CFRelease(uti)
    CFRelease(url)
  }

  return path
}

/**
 * The bytes of the file at [path], for the still that names no file of its own.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun readBytes(path: String): ByteArray {
  val data = NSData.create(contentsOfFile = path) ?: fail("nothing to read at $path")
  val size = data.length.toInt()
  val source = data.bytes
  if (size == 0 || source == null) return ByteArray(0)

  val out = ByteArray(size)
  out.usePinned { pinned -> memcpy(pinned.addressOf(0), source, data.length) }
  return out
}

private const val PNG_UTI = "public.png"
private const val BITS_PER_COMPONENT = 8uL
private const val FULL = 255.0
