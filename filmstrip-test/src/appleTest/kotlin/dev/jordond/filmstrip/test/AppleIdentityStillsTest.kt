package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.heifWithRestatedColour
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
import platform.CoreGraphics.CGImageGetBitsPerComponent
import platform.CoreGraphics.CGImageGetBitsPerPixel
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds the measurement [AppleStillSamples.evaluated] rests on, against ImageIO itself. Every 4:4:4 HEIC at eight and
 * ten bits whose `nclx` names a matrix Apple's decoder does not read exactly, at both ranges, is restated to the
 * identity matrix at full range in its codestream and `nclx`, and ImageIO hands its stored samples back as they are,
 * the red difference as red, luma as green and the blue difference as blue.
 */
@OptIn(InternalFilmstripApi::class, ExperimentalForeignApi::class)
class AppleIdentityStillsTest {
  @Test
  fun imageIoHandsOutTheStoredSamplesOfAStillRestatedToIdentityAtFullRange() {
    val misses =
      UNREAD.flatMap { matrix ->
        listOf(true, false).flatMap { full ->
          val nclx = CicpTag(1, 13, matrix, full)
          listOf(
            Triple("eight-bit $nclx", HeicIdentityPictures.bytes(nclx), HeicIdentityPictures.YCBCR),
            Triple("ten-bit $nclx", HeicIdentityPictures.tenBitBytes(nclx), HeicIdentityPictures.TEN_BIT_YCBCR),
          ).mapNotNull { (name, bytes, ycbcr) ->
            val identity = heifWithRestatedColour(bytes) { _, _, _ -> RestatedColour(0, 1, true, nclx = true) }.restated
            val read = storedCentre(identity)
            val expected = listOf(ycbcr[2], ycbcr[0], ycbcr[1])
            if (read == expected) null else "the $name HEIC handed out $read, where it stores $expected"
          }
        }
      }

    val build = NSProcessInfo.processInfo.operatingSystemVersionString + if (isSimulator()) " simulator" else ""
    assertTrue(misses.isEmpty(), "Misses on $build:\n${misses.joinToString("\n")}")
  }

  // Red, green and blue at the centre of the bitmap ImageIO decodes bytes to, eight-bit RGBX or ten-bit RGB packed
  // into little endian words with red highest, or null for any other layout.
  @OptIn(BetaInteropApi::class)
  private fun storedCentre(bytes: ByteArray): List<Int>? {
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val cf: CFDataRef = checkNotNull(CFBridgingRetain(data)).reinterpret()
    val source = checkNotNull(CGImageSourceCreateWithData(cf, null))
    CFRelease(cf)
    val image = CGImageSourceCreateImageAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), null)
    CFRelease(source)
    if (image == null) return null
    try {
      val samples = CGDataProviderCopyData(CGImageGetDataProvider(image)) ?: return null
      try {
        val pointer = checkNotNull(CFDataGetBytePtr(samples))
        val at =
          CGImageGetHeight(image).toInt() / 2 * CGImageGetBytesPerRow(image).toInt() +
            CGImageGetWidth(image).toInt() / 2 * TEXEL_BYTES
        if (CGImageGetBitsPerPixel(image).toInt() != TEXEL_BYTES * BYTE_BITS) return null
        return when (CGImageGetBitsPerComponent(image).toInt()) {
          EIGHT -> {
            List(3) { pointer[at + it].toInt() }
          }
          TEN -> {
            val word = (0 until TEXEL_BYTES).sumOf { pointer[at + it].toInt() shl (BYTE_BITS * it) }
            listOf(word shr (2 * TEN), word shr TEN, word).map { it and TEN_BIT_MASK }
          }
          else -> {
            null
          }
        }
      } finally {
        CFRelease(samples)
      }
    } finally {
      CGImageRelease(image)
    }
  }

  private companion object {
    // FCC, SMPTE 240M, YCgCo, BT.2020 constant luminance and the identity matrix itself, which at limited range ImageIO
    // hands out no samples for.
    val UNREAD = listOf(0, 4, 7, 8, 10)
    const val TEXEL_BYTES = 4
    const val BYTE_BITS = 8
    const val EIGHT = 8
    const val TEN = 10
    const val TEN_BIT_MASK = 0x3FF
  }
}
