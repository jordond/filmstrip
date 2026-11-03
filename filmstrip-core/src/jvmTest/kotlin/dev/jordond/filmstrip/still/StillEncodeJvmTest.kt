package dev.jordond.filmstrip.still

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.media.SINK_PARENT_MISSING
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test

/**
 * What the JDK's encoder writes, read back through a decoder that never saw the encoder.
 *
 * Counting bytes proves only that something was written, so every format that round trips is
 * decoded and its color compared. WebP is the format the JDK has no writer for, and refusing it by
 * name is the behaviour a caller reads before shipping.
 */
class StillEncodeJvmTest {
  @Test
  fun `a png round trips the color it was given`() =
    runTest {
      val encoded = solidFrame(COLOR).use { it.encodeStill(StillSpec(format = StillFormat.Png)) }

      val success = encoded as StillBytes.Success
      success.format shouldBe StillFormat.Png
      success.size shouldBe Size(FRAME_WIDTH, FRAME_HEIGHT)
      decode(success.bytes).colorAt(0, 0) shouldBe COLOR
    }

  @Test
  fun `a jpeg round trips close to the color it was given`() =
    runTest {
      val encoded = solidFrame(COLOR).use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val success = encoded as StillBytes.Success
      val decoded = decode(success.bytes).colorAt(FRAME_WIDTH / 2, FRAME_HEIGHT / 2)
      withClue("expected roughly ${COLOR.toString(16)}, got ${decoded.toString(16)}") {
        decoded.isNear(COLOR).shouldBeTrue()
      }
    }

  @Test
  fun `webp is refused by name rather than written as something else`() =
    runTest {
      val encoded = solidFrame(COLOR).use { it.encodeStill(StillSpec(format = StillFormat.Webp)) }

      val failure = (encoded as StillBytes.Failure).error as FilmstripError.UnsupportedStillFormat
      failure.format shouldBe StillFormat.Webp
      withClue(failure.message) { failure.message.contains("Webp").shouldBeTrue() }
      withClue(failure.message) { failure.message.contains("ImageIO").shouldBeTrue() }
    }

  @Test
  fun `a height in the spec scales the encoded still`() =
    runTest {
      val wanted = stillSizeOf(Size(FRAME_WIDTH, FRAME_HEIGHT), SCALED_HEIGHT)

      val encoded =
        solidFrame(COLOR).use {
          it.encodeStill(StillSpec(format = StillFormat.Png, heightPx = SCALED_HEIGHT))
        }

      val success = encoded as StillBytes.Success
      success.size shouldBe wanted

      val decoded = decode(success.bytes)
      Size(decoded.width, decoded.height) shouldBe wanted
      decoded.colorAt(0, 0) shouldBe COLOR
    }

  @Test
  fun `two jpeg qualities produce different byte counts`() =
    runTest {
      val low = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 10)) }
      val high = gradientFrame().use { it.encodeStill(StillSpec(format = StillFormat.Jpeg, quality = 95)) }

      val lowBytes = (low as StillBytes.Success).bytes.size
      val highBytes = (high as StillBytes.Success).bytes.size
      withClue("quality 95 wrote $highBytes bytes, quality 10 wrote $lowBytes") {
        (highBytes > lowBytes).shouldBeTrue()
      }
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
      val closed = solidFrame(COLOR).apply { close() }

      val encoded = closed.encodeStill(StillSpec(format = StillFormat.Png))

      (encoded as StillBytes.Failure).error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    }

  @Test
  fun `a still written to a path is on disk and decodes back`() =
    runTest {
      val target = File(scratch(), "still.png")

      val written = writeStill(pngOf(COLOR), MediaSink.Path(target.path), StillFormat.Png)

      (written as StillWrite.Success).output shouldBe MediaSink.Path(target.path)
      withClue("nothing was written to ${target.path}") { target.exists().shouldBeTrue() }
      decode(target.readBytes()).colorAt(0, 0) shouldBe COLOR
    }

  @Test
  fun `a temporary sink comes back as the path it resolved to`() =
    runTest {
      val written = writeStill(pngOf(COLOR), MediaSink.Temporary, StillFormat.Png)

      val path = (written as StillWrite.Success).output as MediaSink.Path
      val file = File(path.path)
      try {
        withClue("nothing was written to ${path.path}") { file.exists().shouldBeTrue() }
        withClue(path.path) { path.path.endsWith(".${StillFormat.Png.fileExtension}").shouldBeTrue() }
        decode(file.readBytes()).colorAt(0, 0) shouldBe COLOR
      } finally {
        file.delete()
      }
    }

  @Test
  fun `a sink whose directory does not exist fails rather than throwing`() =
    runTest {
      val missing = File(scratch(), "nowhere/still.png")

      val written = writeStill(pngOf(COLOR), MediaSink.Path(missing.path), StillFormat.Png)

      val refusal = (written as StillWrite.Failure).error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      refusal.sink shouldBe missing.path
      refusal.message shouldBe SINK_PARENT_MISSING
      withClue("a refused write still left a file behind") { (!missing.exists()).shouldBeTrue() }
    }

  private suspend fun pngOf(color: Int): ByteArray =
    (solidFrame(color).use { it.encodeStill(StillSpec(format = StillFormat.Png)) } as StillBytes.Success).bytes

  private fun scratch(): File = createTempDirectory("filmstrip-still").toFile().apply { deleteOnExit() }

  private fun decode(bytes: ByteArray): BufferedImage =
    checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "the encoded still did not decode back" }

  private fun BufferedImage.colorAt(
    x: Int,
    y: Int,
  ): Int = getRGB(x, y) or ALPHA

  private fun Int.isNear(other: Int): Boolean =
    CHANNELS.all { shift -> abs(((this shr shift) and BYTE) - ((other shr shift) and BYTE)) <= TOLERANCE }

  private companion object {
    const val FRAME_WIDTH = 200
    const val FRAME_HEIGHT = 100
    const val SCALED_HEIGHT = 40
    const val COLOR = 0xFF3366CC.toInt()
    const val ALPHA = 0xFF shl 24
    const val BYTE = 0xFF
    const val TOLERANCE = 8
    const val CHANNEL = 256

    val CHANNELS = listOf(16, 8, 0)

    fun solidFrame(color: Int): PlatformImage = frame { _, _ -> color }

    /**
     * A deterministic gradient, which is what gives a JPEG encoder something to spend quality on.
     * A flat color compresses to nearly the same size whatever quality is asked for.
     */
    fun gradientFrame(): PlatformImage =
      frame { x, y -> ALPHA or ((x * 7 % CHANNEL) shl 16) or ((y * 13 % CHANNEL) shl 8) or ((x * y) % CHANNEL) }

    fun frame(color: (Int, Int) -> Int): PlatformImage {
      val image = BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB)
      for (y in 0 until FRAME_HEIGHT) {
        for (x in 0 until FRAME_WIDTH) {
          image.setRGB(x, y, color(x, y))
        }
      }
      return PlatformImage(image)
    }
  }
}
