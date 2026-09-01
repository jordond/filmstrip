@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the canvas encodes, decoded back through `createImageBitmap`, which never saw the encoder.
 *
 * WebP is where browsers differ, and one that will not encode it falls back to PNG rather than
 * failing, so the assertion is against what came back rather than against a fixed expectation.
 */
class StillEncodeWebTest {
  @Test
  fun aPngRoundTripsTheColourItWasGiven() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Png)) }

      val success = assertIs<StillBytes.Success>(encoded)
      assertEquals(Size(FRAME_WIDTH, FRAME_HEIGHT), success.size)
      assertNear(COLOUR, success.bytes.firstPixel(StillFormat.Png), tolerance = 0)
    }

  @Test
  fun aJpegRoundTripsCloseToTheColourItWasGiven() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val success = assertIs<StillBytes.Success>(encoded)
      assertNear(COLOUR, success.bytes.firstPixel(StillFormat.Jpeg), tolerance = TOLERANCE)
    }

  @Test
  fun webpEitherRoundTripsOrIsRefusedByName() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Webp, quality = 100)) }

      when (encoded) {
        is StillBytes.Success -> {
          assertNear(COLOUR, encoded.bytes.firstPixel(StillFormat.Webp), tolerance = TOLERANCE)
        }
        is StillBytes.Failure -> {
          val error = assertIs<ExportError.UnsupportedStillFormat>(encoded.error)
          assertEquals(StillFormat.Webp, error.format)
          assertTrue(error.message.contains("Webp"), error.message)
          assertTrue(error.message.contains("browser"), error.message)
        }
      }
    }

  @Test
  fun aHeightInTheSpecScalesTheEncodedStill() =
    runTest {
      val wanted = stillSizeOf(Size(FRAME_WIDTH, FRAME_HEIGHT), SCALED_HEIGHT)

      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Png, heightPx = SCALED_HEIGHT)) }

      val success = assertIs<StillBytes.Success>(encoded)
      assertEquals(wanted, success.size)
      assertEquals(wanted, success.bytes.decodedSize(StillFormat.Png))
      assertNear(COLOUR, success.bytes.firstPixel(StillFormat.Png), tolerance = 0)
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
  fun aClosedFrameIsAFailureRatherThanAnEmptyBlob() =
    runTest {
      val closed = frame().apply { close() }

      val encoded = closed.encodeStill(StillSpec(format = StillFormat.Png))

      assertIs<ExportError.SourceUnreadable>(assertIs<StillBytes.Failure>(encoded).error)
    }

  @Test
  fun aUriSinkHandsBackAnObjectUrl() =
    runTest {
      val bytes = assertIs<StillBytes.Success>(frame().use { it.encodeStill(StillSpec(StillFormat.Png)) }).bytes

      val written = writeStill(bytes, MediaSink.Uri("still.png"), StillFormat.Png)

      val output = assertIs<MediaSink.Uri>(assertIs<StillWrite.Success>(written).output)
      assertTrue(output.uri.startsWith("blob:"), output.uri)
      URL.revokeObjectURL(output.uri)
    }

  @Test
  fun aTemporarySinkComesBackAsTheNameItWasSavedUnder() =
    runTest {
      val bytes = assertIs<StillBytes.Success>(frame().use { it.encodeStill(StillSpec(StillFormat.Png)) }).bytes

      val written = writeStill(bytes, MediaSink.Temporary, StillFormat.Png)

      val output = assertIs<MediaSink.Path>(assertIs<StillWrite.Success>(written).output)
      assertTrue(output.path.endsWith(".${StillFormat.Png.fileExtension}"), output.path)
    }

  private suspend fun ByteArray.firstPixel(format: StillFormat): List<Int> {
    val pixels = decodedPixels(this, format, 1, 1)
    return CHANNELS.map { pixels.channel(it) }
  }

  private suspend fun ByteArray.decodedSize(format: StillFormat): Size {
    val bitmap = createImageBitmap(blobOf(this, format.mimeType)).await()
    return Size(bitmap.width, bitmap.height)
  }

  private suspend fun decodedPixels(
    bytes: ByteArray,
    format: StillFormat,
    width: Int,
    height: Int,
  ): ImageData {
    val bitmap = createImageBitmap(blobOf(bytes, format.mimeType)).await()
    val canvas = OffscreenCanvas(bitmap.width, bitmap.height)
    val context = checkNotNull(canvas.getContext("2d")) { "this browser gave back no 2D context" }
    context.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height)
    return context.getImageData(0, 0, width, height)
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
    const val OPAQUE = 0xFF.toByte()
    const val CHANNEL = 256
    const val TOLERANCE = 8

    val COLOUR = listOf(0x33, 0x66, 0xCC)
    val CHANNELS = listOf(0, 1, 2)

    fun frame(): PlatformImage = platformImage { _, _ -> COLOUR }

    /**
     * A deterministic gradient, which is what gives a JPEG encoder something to spend quality on. A
     * flat colour compresses to nearly the same size whatever quality is asked for.
     */
    fun gradientFrame(): PlatformImage =
      platformImage { x, y -> listOf(x * 7 % CHANNEL, y * 13 % CHANNEL, x * y % CHANNEL) }

    fun platformImage(colour: (Int, Int) -> List<Int>): PlatformImage {
      val pixels = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4)
      for (y in 0 until FRAME_HEIGHT) {
        for (x in 0 until FRAME_WIDTH) {
          val offset = (y * FRAME_WIDTH + x) * 4
          val rgb = colour(x, y)
          pixels[offset] = rgb[0].toByte()
          pixels[offset + 1] = rgb[1].toByte()
          pixels[offset + 2] = rgb[2].toByte()
          pixels[offset + 3] = OPAQUE
        }
      }
      return PlatformImage(FRAME_WIDTH, FRAME_HEIGHT, pixels)
    }
  }
}

/**
 * The decoder the test reads an encoded still back through. It never saw the canvas that wrote it.
 */
internal external fun createImageBitmap(image: Blob): Promise<ImageBitmap>

internal external interface ImageBitmap : JsAny {
  val width: Int

  val height: Int
}

/**
 * One channel of a decoded pixel. Reading into an `ImageData` is one of the few things js and
 * wasmJs spell differently.
 */
internal expect fun ImageData.channel(index: Int): Int
