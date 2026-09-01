package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGImageDestinationCopyTypeIdentifiers
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What ImageIO writes, read back through an image source that never saw the destination.
 *
 * WebP is the format whose answer depends on the system rather than on filmstrip, so it is asserted
 * against what ImageIO itself says it will write instead of against a fixed expectation.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class StillEncodeAppleTest {
  @Test
  fun aPngRoundTripsTheColourItWasGiven() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Png)) }

      val success = assertIs<StillBytes.Success>(encoded)
      assertEquals(Size(FRAME_WIDTH, FRAME_HEIGHT), success.size)
      assertNear(COLOUR, success.bytes.firstPixel(), tolerance = 0)
    }

  @Test
  fun aJpegRoundTripsCloseToTheColourItWasGiven() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val success = assertIs<StillBytes.Success>(encoded)
      assertNear(COLOUR, success.bytes.firstPixel(), tolerance = TOLERANCE)
    }

  @Test
  fun webpAgreesWithWhatThisSystemSaysItWillWrite() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Webp)) }

      if (writableTypes().contains(WEBP_UTI)) {
        assertIs<StillBytes.Success>(encoded)
      } else {
        val error = assertIs<ExportError.UnsupportedStillFormat>(assertIs<StillBytes.Failure>(encoded).error)
        assertEquals(StillFormat.Webp, error.format)
        assertTrue(error.message.contains("Webp"), error.message)
        assertTrue(error.message.contains("ImageIO"), error.message)
      }
    }

  @Test
  fun aHeightInTheSpecScalesTheEncodedStill() =
    runTest {
      val wanted = stillSizeOf(Size(FRAME_WIDTH, FRAME_HEIGHT), SCALED_HEIGHT)

      val encoded =
        frame().use { it.encodeStill(StillSpec(format = StillFormat.Png, heightPx = SCALED_HEIGHT)) }

      val success = assertIs<StillBytes.Success>(encoded)
      assertEquals(wanted, success.size)
      assertEquals(wanted, success.bytes.decodedSize())
      assertNear(COLOUR, success.bytes.firstPixel(), tolerance = 0)
    }

  @Test
  fun twoJpegQualitiesProduceDifferentByteCounts() =
    runTest {
      val low = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 10)) }
      val high = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val lowBytes = assertIs<StillBytes.Success>(low).bytes.size
      val highBytes = assertIs<StillBytes.Success>(high).bytes.size
      assertTrue(highBytes > lowBytes, "quality 95 wrote $highBytes bytes, quality 10 wrote $lowBytes")
    }

  @Test
  fun aClosedFrameIsAFailureRatherThanAnEmptyFile() =
    runTest {
      val closed = frame().apply { close() }

      val encoded = closed.encodeStill(StillSpec(format = StillFormat.Png))

      assertIs<ExportError.SourceUnreadable>(assertIs<StillBytes.Failure>(encoded).error)
    }

  @Test
  fun aTemporarySinkComesBackAsThePathItResolvedTo() =
    runTest {
      val bytes = assertIs<StillBytes.Success>(frame().use { it.encodeStill(StillSpec(StillFormat.Png)) }).bytes

      val written = writeStill(bytes, MediaSink.Temporary, StillFormat.Png)

      val path = assertIs<MediaSink.Path>(assertIs<StillWrite.Success>(written).output)
      assertTrue(path.path.endsWith(".${StillFormat.Png.fileExtension}"), path.path)
      try {
        assertTrue(readBack(path.path).contentEquals(bytes), "the file on disk is not what was encoded")
      } finally {
        SystemFileSystem.delete(Path(path.path), mustExist = false)
      }
    }

  /**
   * The uniform type identifiers ImageIO says it can write on this system, which is the only honest
   * expectation for WebP.
   */
  private fun writableTypes(): List<String> =
    (CFBridgingRelease(CGImageDestinationCopyTypeIdentifiers()) as? List<*>)
      .orEmpty()
      .map { it.toString() }

  private fun readBack(path: String): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

  /**
   * The top-left pixel of an encoded still, decoded through an image source.
   */
  private fun ByteArray.firstPixel(): List<Int> =
    decoded().use { image ->
      val pixels = image.toRgba8888()
      CHANNELS.map { pixels[it].toInt() and BYTE }
    }

  private fun ByteArray.decodedSize(): Size = decoded().use { Size(it.widthPx, it.heightPx) }

  private fun ByteArray.decoded(): PlatformImage {
    val data = nsDataOf(this)
    val cfData = checkNotNull(CFBridgingRetain(data)).asData()
    return try {
      val source = checkNotNull(CGImageSourceCreateWithData(cfData, null)) { "the still did not open as an image" }
      try {
        PlatformImage(checkNotNull(CGImageSourceCreateImageAtIndex(source, 0u, null)))
      } finally {
        CFRelease(source)
      }
    } finally {
      CFRelease(cfData)
    }
  }

  private fun assertNear(
    expected: List<Int>,
    actual: List<Int>,
    tolerance: Int,
  ) {
    val near = expected.indices.all { abs(expected[it] - actual[it]) <= tolerance }
    assertTrue(near, "expected roughly $expected, got $actual")
  }

  private companion object {
    const val FRAME_WIDTH = 200
    const val FRAME_HEIGHT = 100
    const val SCALED_HEIGHT = 40
    const val BYTE = 0xFF
    const val TOLERANCE = 8
    const val WEBP_UTI = "org.webmproject.webp"

    val COLOUR = listOf(0x33, 0x66, 0xCC)
    val CHANNELS = listOf(0, 1, 2)

    fun frame(): PlatformImage = PlatformImage(solidImage())

    fun gradientFrame(): PlatformImage = PlatformImage(stripedImage())

    /**
     * A flat fill of [COLOUR], which is what a round trip has to give back.
     */
    fun solidImage(): CGImageRef =
      drawn { context ->
        context.fill(COLOUR[0], COLOUR[1], COLOUR[2], 0.0, FRAME_WIDTH.toDouble())
      }

    /**
     * Vertical stripes, which give a JPEG encoder something to spend quality on. A flat colour
     * compresses to nearly the same size whatever quality is asked for.
     */
    fun stripedImage(): CGImageRef =
      drawn { context ->
        var x = 0
        while (x < FRAME_WIDTH) {
          context.fill(x * 11 % (BYTE + 1), x * 29 % (BYTE + 1), x * 47 % (BYTE + 1), x.toDouble(), 1.0)
          x++
        }
      }

    fun drawn(draw: (CGContextRef) -> Unit): CGImageRef {
      val space = CGColorSpaceCreateDeviceRGB()
      try {
        val context =
          checkNotNull(
            CGBitmapContextCreate(
              data = null,
              width = FRAME_WIDTH.toULong(),
              height = FRAME_HEIGHT.toULong(),
              bitsPerComponent = 8uL,
              bytesPerRow = 0uL,
              space = space,
              bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ),
          )
        return try {
          draw(context)
          checkNotNull(CGBitmapContextCreateImage(context))
        } finally {
          CGContextRelease(context)
        }
      } finally {
        CGColorSpaceRelease(space)
      }
    }

    fun CGContextRef.fill(
      red: Int,
      green: Int,
      blue: Int,
      x: Double,
      width: Double,
    ) {
      CGContextSetRGBFillColor(this, red / BYTE.toDouble(), green / BYTE.toDouble(), blue / BYTE.toDouble(), 1.0)
      CGContextFillRect(this, CGRectMake(x, 0.0, width, FRAME_HEIGHT.toDouble()))
    }

    fun nsDataOf(bytes: ByteArray): NSData =
      bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
      }

    fun COpaquePointer.asData(): CFDataRef = reinterpret()
  }
}
