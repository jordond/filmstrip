@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.still

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.Blob
import dev.jordond.filmstrip.media.ImageBitmap
import dev.jordond.filmstrip.media.ImageData
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.OffscreenCanvas
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.media.URL
import dev.jordond.filmstrip.media.blobOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test

/**
 * What the canvas encodes, decoded back through `createImageBitmap`, which never saw the encoder.
 *
 * WebP is where browsers differ, and one that will not encode it falls back to PNG rather than
 * failing, so the assertion is against what came back rather than against a fixed expectation.
 */
class StillEncodeWebTest {
  @Test
  fun aPngRoundTripsTheColorItWasGiven() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Png)) }

      val success = encoded.shouldBeInstanceOf<StillBytes.Success>()
      success.size shouldBe Size(FRAME_WIDTH, FRAME_HEIGHT)
      assertNear(COLOR, success.bytes.firstPixel(StillFormat.Png), tolerance = 0)
    }

  @Test
  fun aJpegRoundTripsCloseToTheColorItWasGiven() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val success = encoded.shouldBeInstanceOf<StillBytes.Success>()
      assertNear(COLOR, success.bytes.firstPixel(StillFormat.Jpeg), tolerance = TOLERANCE)
    }

  @Test
  fun webpEitherRoundTripsOrIsRefusedByName() =
    runTest {
      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Webp, quality = 100)) }

      when (encoded) {
        is StillBytes.Success -> {
          assertNear(COLOR, encoded.bytes.firstPixel(StillFormat.Webp), tolerance = TOLERANCE)
        }
        is StillBytes.Failure -> {
          val error = encoded.error.shouldBeInstanceOf<FilmstripError.UnsupportedStillFormat>()
          error.format shouldBe StillFormat.Webp
          withClue(error.message) { error.message.contains("Webp").shouldBeTrue() }
          withClue(error.message) { error.message.contains("browser").shouldBeTrue() }
        }
      }
    }

  @Test
  fun aHeightInTheSpecScalesTheEncodedStill() =
    runTest {
      val wanted = stillSizeOf(Size(FRAME_WIDTH, FRAME_HEIGHT), SCALED_HEIGHT)

      val encoded = frame().use { it.encodeStill(StillSpec(format = StillFormat.Png, heightPx = SCALED_HEIGHT)) }

      val success = encoded.shouldBeInstanceOf<StillBytes.Success>()
      success.size shouldBe wanted
      success.bytes.decodedSize(StillFormat.Png) shouldBe wanted
      assertNear(COLOR, success.bytes.firstPixel(StillFormat.Png), tolerance = 0)
    }

  @Test
  fun twoJpegQualitiesProduceDifferentByteCounts() =
    runTest {
      val low = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 10)) }
      val high = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val lowBytes = low.shouldBeInstanceOf<StillBytes.Success>().bytes.size
      val highBytes = high.shouldBeInstanceOf<StillBytes.Success>().bytes.size
      withClue("quality 95 wrote $highBytes bytes, quality 10 wrote $lowBytes") {
        (highBytes > lowBytes).shouldBeTrue()
      }
    }

  @Test
  fun aClosedFrameIsAFailureRatherThanAnEmptyBlob() =
    runTest {
      val closed = frame().apply { close() }

      val encoded = closed.encodeStill(StillSpec(format = StillFormat.Png))

      encoded.shouldBeInstanceOf<StillBytes.Failure>().error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    }

  // The job is cancelled before the encoder is awaited, so the await is the first thing to see it.
  @Test
  fun aCancelledEncodeCancelsRatherThanFailing() =
    runTest {
      var encoded: StillBytes? = null
      val job =
        launch {
          cancel()
          encoded = frame().use { it.encode(StillSpec(format = StillFormat.Png), Size(FRAME_WIDTH, FRAME_HEIGHT)) }
        }
      job.join()

      withClue("a cancelled encode answered instead of cancelling") { encoded.shouldBeNull() }
    }

  @Test
  fun aUriSinkHandsBackAnObjectUrl() =
    runTest {
      val bytes =
        frame()
          .use {
            it.encodeStill(
              StillSpec(StillFormat.Png),
            )
          }.shouldBeInstanceOf<StillBytes.Success>()
          .bytes

      val written = writeStill(bytes, MediaSink.Uri("still.png"), StillFormat.Png)

      val output = written.shouldBeInstanceOf<StillWrite.Success>().output.shouldBeInstanceOf<MediaSink.Uri>()
      withClue(output.uri) { output.uri.startsWith("blob:").shouldBeTrue() }
      URL.revokeObjectURL(output.uri)
    }

  @Test
  fun aTemporarySinkComesBackAsTheNameItWasSavedUnder() =
    runTest {
      val bytes =
        frame()
          .use {
            it.encodeStill(
              StillSpec(StillFormat.Png),
            )
          }.shouldBeInstanceOf<StillBytes.Success>()
          .bytes

      val written = writeStill(bytes, MediaSink.Temporary, StillFormat.Png)

      val output = written.shouldBeInstanceOf<StillWrite.Success>().output.shouldBeInstanceOf<MediaSink.Path>()
      withClue(output.path) { output.path.endsWith(".${StillFormat.Png.fileExtension}").shouldBeTrue() }
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
    withClue("expected roughly $expected, got $actual") { near.shouldBeTrue() }
  }

  private companion object {
    const val FRAME_WIDTH = 200
    const val FRAME_HEIGHT = 100
    const val SCALED_HEIGHT = 40
    const val OPAQUE = 0xFF.toByte()
    const val CHANNEL = 256
    const val TOLERANCE = 8

    val COLOR = listOf(0x33, 0x66, 0xCC)
    val CHANNELS = listOf(0, 1, 2)

    fun frame(): PlatformImage = platformImage { _, _ -> COLOR }

    /**
     * A deterministic gradient, which is what gives a JPEG encoder something to spend quality on. A
     * flat color compresses to nearly the same size whatever quality is asked for.
     */
    fun gradientFrame(): PlatformImage =
      platformImage { x, y -> listOf(x * 7 % CHANNEL, y * 13 % CHANNEL, x * y % CHANNEL) }

    fun platformImage(color: (Int, Int) -> List<Int>): PlatformImage {
      val pixels = ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 4)
      for (y in 0 until FRAME_HEIGHT) {
        for (x in 0 until FRAME_WIDTH) {
          val offset = (y * FRAME_WIDTH + x) * 4
          val rgb = color(x, y)
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

/**
 * One channel of a decoded pixel. Reading into an `ImageData` is one of the few things js and
 * wasmJs spell differently.
 */
internal expect fun ImageData.channel(index: Int): Int
