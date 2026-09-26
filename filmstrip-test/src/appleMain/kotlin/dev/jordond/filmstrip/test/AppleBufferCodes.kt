package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGColorCreate
import platform.CoreGraphics.CGColorCreateCopyByMatchingToColorSpace
import platform.CoreGraphics.CGColorGetComponents
import platform.CoreGraphics.CGColorRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreVideo.CVImageBufferCreateColorSpaceFromAttachments
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2

/**
 * Reads and moves colours in the codes an SDR Apple export's BT.709 buffer holds.
 *
 * A probe that draws a decoded frame into device RGB reads it colour matched out of the codes the file was written
 * with. [fromDevice] and [toDevice] carry a colour between the two with Core Graphics, and [videoRangeCodesIn] reads
 * the codes straight off a Y'CbCr buffer with no colour matching at all.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public object AppleBufferCodes {
  /**
   * These device RGB channels matched into the BT.709 codes an SDR buffer holds.
   *
   * Both sides are red, green and blue as fractions of one.
   */
  public fun fromDevice(channels: FloatArray): FloatArray = matched(channels, toBuffer = true)

  /**
   * These BT.709 buffer codes matched into device RGB, the way a probe drawing the frame reads them.
   *
   * Both sides are red, green and blue as fractions of one.
   */
  public fun toDevice(codes: FloatArray): FloatArray = matched(codes, toBuffer = false)

  /**
   * The R'G'B' codes averaged over a [width] by [height] rect at ([left], [top]) of [buffer], in `0..255` and
   * unrounded.
   *
   * The buffer is eight-bit bi-planar video range Y'CbCr. Its luma and chroma samples are averaged and carried to
   * R'G'B' through the BT.709 matrix, so what comes back is the codes the buffer was written with.
   */
  public fun videoRangeCodesIn(
    buffer: CVPixelBufferRef,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
  ): FloatArray {
    CVPixelBufferLockBaseAddress(buffer, READ_ONLY)
    try {
      val luma = checkNotNull(CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)?.reinterpret<ByteVar>()) { "no luma" }
      val chroma = checkNotNull(CVPixelBufferGetBaseAddressOfPlane(buffer, 1u)?.reinterpret<ByteVar>()) { "no chroma" }
      val lumaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
      val chromaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1u).toInt()

      var y = 0f
      var cb = 0f
      var cr = 0f
      for (row in top until top + height) {
        for (column in left until left + width) {
          y += luma[row * lumaStride + column].toInt() and BYTE_MASK
          val pair = (row / 2) * chromaStride + (column / 2) * 2
          cb += chroma[pair].toInt() and BYTE_MASK
          cr += chroma[pair + 1].toInt() and BYTE_MASK
        }
      }

      val area = (width * height).toFloat()
      val lumaLevel = (y / area - LUMA_FOOT) / LUMA_SPAN
      val blueDiff = (cb / area - CHROMA_MID) / CHROMA_SPAN
      val redDiff = (cr / area - CHROMA_MID) / CHROMA_SPAN
      return floatArrayOf(
        (lumaLevel + KR_CR * redDiff) * FULL,
        (lumaLevel - KG_CB * blueDiff - KG_CR * redDiff) * FULL,
        (lumaLevel + KB_CB * blueDiff) * FULL,
      )
    } finally {
      CVPixelBufferUnlockBaseAddress(buffer, READ_ONLY)
    }
  }

  private fun matched(
    channels: FloatArray,
    toBuffer: Boolean,
  ): FloatArray =
    memScoped {
      val device = CGColorSpaceCreateDeviceRGB()
      val buffer = bt709BufferSpace()
      val (from: CGColorSpaceRef?, to: CGColorSpaceRef?) = if (toBuffer) device to buffer else buffer to device
      val components = allocArray<DoubleVar>(CHANNELS + 1)
      repeat(CHANNELS) { components[it] = channels[it].toDouble() }
      components[CHANNELS] = 1.0
      val color = CGColorCreate(from, components)
      val moved =
        CGColorCreateCopyByMatchingToColorSpace(to, CGColorRenderingIntent.kCGRenderingIntentDefault, color, null)
      try {
        val out = checkNotNull(CGColorGetComponents(moved)) { "could not match a colour into the other space" }
        FloatArray(CHANNELS) { out[it].toFloat() }
      } finally {
        CGColorRelease(moved)
        CGColorRelease(color)
        CGColorSpaceRelease(buffer)
        CGColorSpaceRelease(device)
      }
    }

  /**
   * Creates the colour space CoreVideo builds for a buffer tagged with BT.709 primaries, transfer and matrix. The
   * caller releases it.
   *
   * A colour tagged with this space lands in such a buffer as the codes it was given.
   */
  private fun bt709BufferSpace(): CGColorSpaceRef? {
    val keys = kCFTypeDictionaryKeyCallBacks.ptr
    val values = kCFTypeDictionaryValueCallBacks.ptr
    val tags = CFDictionaryCreateMutable(null, BT709_TAGS, keys, values)
    CFDictionarySetValue(tags, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2)
    CFDictionarySetValue(tags, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2)
    CFDictionarySetValue(tags, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2)
    try {
      return CVImageBufferCreateColorSpaceFromAttachments(tags)
    } finally {
      CFRelease(tags)
    }
  }

  private const val CHANNELS = 3
  private const val BT709_TAGS = 3L
  private const val BYTE_MASK = 0xFF
  private const val FULL = 255f
  private const val READ_ONLY = 1uL

  // BT.709 video range: luma 16 to 235, chroma centred on 128 over 224 codes.
  private const val LUMA_FOOT = 16f
  private const val LUMA_SPAN = 219f
  private const val CHROMA_MID = 128f
  private const val CHROMA_SPAN = 224f
  private const val KR_CR = 1.5748f
  private const val KG_CB = 0.1873f
  private const val KG_CR = 0.4681f
  private const val KB_CB = 1.8556f
}
