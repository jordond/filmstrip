package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the JDK's encoder writes, read back through a decoder that never saw the encoder.
 *
 * Counting bytes proves only that something was written, so every format that round trips is
 * decoded and its colour compared. WebP is the format the JDK has no writer for, and refusing it by
 * name is the behaviour a caller reads before shipping.
 */
class StillEncodeJvmTest {
  @Test
  fun `a png round trips the colour it was given`() =
    runTest {
      val encoded = solidFrame(COLOUR).use { it.encodeStill(StillSpec(format = StillFormat.Png)) }

      val success = encoded as StillBytes.Success
      success.format shouldBe StillFormat.Png
      success.size shouldBe Size(FRAME_WIDTH, FRAME_HEIGHT)
      decode(success.bytes).colourAt(0, 0) shouldBe COLOUR
    }

  @Test
  fun `a jpeg round trips close to the colour it was given`() =
    runTest {
      val encoded = solidFrame(COLOUR).use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val success = encoded as StillBytes.Success
      val decoded = decode(success.bytes).colourAt(FRAME_WIDTH / 2, FRAME_HEIGHT / 2)
      assertTrue(decoded.isNear(COLOUR), "expected roughly ${COLOUR.toString(16)}, got ${decoded.toString(16)}")
    }

  @Test
  fun `webp is refused by name rather than written as something else`() =
    runTest {
      val encoded = solidFrame(COLOUR).use { it.encodeStill(StillSpec(format = StillFormat.Webp)) }

      val failure = (encoded as StillBytes.Failure).error as ExportError.UnsupportedStillFormat
      failure.format shouldBe StillFormat.Webp
      assertTrue(failure.message.contains("Webp"), failure.message)
      assertTrue(failure.message.contains("ImageIO"), failure.message)
    }

  @Test
  fun `a height in the spec scales the encoded still`() =
    runTest {
      val wanted = stillSizeOf(Size(FRAME_WIDTH, FRAME_HEIGHT), SCALED_HEIGHT)

      val encoded =
        solidFrame(COLOUR).use {
          it.encodeStill(StillSpec(format = StillFormat.Png, heightPx = SCALED_HEIGHT))
        }

      val success = encoded as StillBytes.Success
      success.size shouldBe wanted

      val decoded = decode(success.bytes)
      Size(decoded.width, decoded.height) shouldBe wanted
      decoded.colourAt(0, 0) shouldBe COLOUR
    }

  @Test
  fun `two jpeg qualities produce different byte counts`() =
    runTest {
      val low = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 10)) }
      val high = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val lowBytes = (low as StillBytes.Success).bytes.size
      val highBytes = (high as StillBytes.Success).bytes.size
      assertTrue(highBytes > lowBytes, "quality 95 wrote $highBytes bytes, quality 10 wrote $lowBytes")
    }

  @Test
  fun `a quality above the range encodes the same as the top of it`() =
    runTest {
      val clamped = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 1000)) }
      val top = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 100)) }

      (clamped as StillBytes.Success).bytes.size shouldBe (top as StillBytes.Success).bytes.size
    }

  @Test
  fun `a closed frame is a failure rather than an empty file`() =
    runTest {
      val closed = solidFrame(COLOUR).apply { close() }

      val encoded = closed.encodeStill(StillSpec(format = StillFormat.Png))

      assertIs<ExportError.SourceUnreadable>((encoded as StillBytes.Failure).error)
    }

  @Test
  fun `a still written to a path is on disk and decodes back`() =
    runTest {
      val target = File(scratch(), "still.png")

      val written = writeStill(pngOf(COLOUR), MediaSink.Path(target.path), StillFormat.Png)

      (written as StillWrite.Success).output shouldBe MediaSink.Path(target.path)
      assertTrue(target.exists(), "nothing was written to ${target.path}")
      decode(target.readBytes()).colourAt(0, 0) shouldBe COLOUR
    }

  @Test
  fun `a temporary sink comes back as the path it resolved to`() =
    runTest {
      val written = writeStill(pngOf(COLOUR), MediaSink.Temporary, StillFormat.Png)

      val path = (written as StillWrite.Success).output as MediaSink.Path
      val file = File(path.path)
      try {
        assertTrue(file.exists(), "nothing was written to ${path.path}")
        assertTrue(path.path.endsWith(".${StillFormat.Png.fileExtension}"), path.path)
        decode(file.readBytes()).colourAt(0, 0) shouldBe COLOUR
      } finally {
        file.delete()
      }
    }

  @Test
  fun `a sink whose directory does not exist fails rather than throwing`() =
    runTest {
      val missing = File(scratch(), "nowhere/still.png")

      val written = writeStill(pngOf(COLOUR), MediaSink.Path(missing.path), StillFormat.Png)

      assertIs<ExportError.SinkUnwritable>((written as StillWrite.Failure).error)
      assertTrue(!missing.exists(), "a refused write still left a file behind")
    }

  private suspend fun pngOf(colour: Int): ByteArray =
    (solidFrame(colour).use { it.encodeStill(StillSpec(format = StillFormat.Png)) } as StillBytes.Success).bytes

  private fun scratch(): File = createTempDirectory("filmstrip-still").toFile().apply { deleteOnExit() }

  private fun decode(bytes: ByteArray): BufferedImage =
    checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "the encoded still did not decode back" }

  private fun BufferedImage.colourAt(
    x: Int,
    y: Int,
  ): Int = getRGB(x, y) or ALPHA

  private fun Int.isNear(other: Int): Boolean =
    CHANNELS.all { shift -> abs(((this shr shift) and BYTE) - ((other shr shift) and BYTE)) <= TOLERANCE }

  private companion object {
    const val FRAME_WIDTH = 200
    const val FRAME_HEIGHT = 100
    const val SCALED_HEIGHT = 40
    const val COLOUR = 0xFF3366CC.toInt()
    const val ALPHA = 0xFF shl 24
    const val BYTE = 0xFF
    const val TOLERANCE = 8
    const val CHANNEL = 256

    val CHANNELS = listOf(16, 8, 0)

    fun solidFrame(colour: Int): PlatformImage = frame { _, _ -> colour }

    /**
     * A deterministic gradient, which is what gives a JPEG encoder something to spend quality on.
     * A flat colour compresses to nearly the same size whatever quality is asked for.
     */
    fun gradientFrame(): PlatformImage =
      frame { x, y -> ALPHA or ((x * 7 % CHANNEL) shl 16) or ((y * 13 % CHANNEL) shl 8) or ((x * y) % CHANNEL) }

    fun frame(colour: (Int, Int) -> Int): PlatformImage {
      val image = BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB)
      for (y in 0 until FRAME_HEIGHT) {
        for (x in 0 until FRAME_WIDTH) {
          image.setRGB(x, y, colour(x, y))
        }
      }
      return PlatformImage(image)
    }
  }
}
