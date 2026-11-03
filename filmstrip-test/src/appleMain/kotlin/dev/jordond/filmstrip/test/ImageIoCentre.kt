package dev.jordond.filmstrip.test

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetBitmapInfo
import platform.CoreGraphics.CGImageGetBitsPerComponent
import platform.CoreGraphics.CGImageGetBitsPerPixel
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapAlphaInfoMask
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrderMask
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import kotlin.test.fail

// The eight-bit red, green and blue ImageIO decodes bytes to at their centre, copied out of its own bitmap.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun imageIoCentre(bytes: ByteArray): List<Int> {
  val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
  val cf: CFDataRef = checkNotNull(CFBridgingRetain(data)).reinterpret()
  val source = checkNotNull(CGImageSourceCreateWithData(cf, null))
  CFRelease(cf)
  val image = checkNotNull(CGImageSourceCreateImageAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), null))
  CFRelease(source)
  try {
    val info = CGImageGetBitmapInfo(image)
    val alpha = info and kCGBitmapAlphaInfoMask
    val order = info and kCGBitmapByteOrderMask
    val readable =
      CGImageGetBitsPerComponent(image) == 8uL && CGImageGetBitsPerPixel(image) == 32uL &&
        (order == 0u || order == kCGBitmapByteOrder32Big) && alpha in RGB_FIRST
    if (!readable) fail("ImageIO decoded to a layout this read does not take, bitmap info $info")
    val samples = checkNotNull(CGDataProviderCopyData(CGImageGetDataProvider(image)))
    try {
      val pointer = checkNotNull(CFDataGetBytePtr(samples))
      val row = CGImageGetBytesPerRow(image).toInt()
      val centre = CGImageGetHeight(image).toInt() / 2 * row + CGImageGetWidth(image).toInt() / 2 * TEXEL_BYTES
      return List(RGB) { pointer[centre + it].toInt() and BYTE }
    } finally {
      CFRelease(samples)
    }
  } finally {
    CGImageRelease(image)
  }
}

// The alpha layouts that put red, green and blue in the first three bytes of a texel.
internal val RGB_FIRST: List<UInt> =
  listOf(
    CGImageAlphaInfo.kCGImageAlphaLast,
    CGImageAlphaInfo.kCGImageAlphaNoneSkipLast,
    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast,
  ).map { it.value }

private const val RGB = 3
private const val TEXEL_BYTES = 4
private const val BYTE = 0xFF
