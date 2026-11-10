package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import dev.jordond.filmstrip.media.probe.MIRRORED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.ROTATED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.imageProbeBytes
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class ImageMeasureJvmTest {
  private val temporaryDirectory = createTempDirectory("filmstrip-image-measure").toFile()

  @Test
  fun `a bitmap in memory measures to its bounds under the name ImageIO gives it`() =
    runTest {
      val measured =
        measureImage(
          ImageSource.ofBytes(imageProbeBytes()),
        ).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe EXIF_ORIENTATION_NORMAL
      measured.format shouldBe "bmp"
    }

  // ImageIO reports no orientation, so a file's tag has to come off the ranged read rather than the reader.
  @Test
  fun `a tagged jpeg on disk reports its stored bounds and its tag`() =
    runTest {
      val file = temporaryDirectory.resolve("sideways.jpg").apply { writeBytes(rotatedImageProbeBytes()) }

      val measured = measureImage(ImageSource.of(file.path)).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe ROTATED_IMAGE_PROBE_ORIENTATION
      measured.format shouldBe "jpeg"
    }

  @Test
  fun `a little endian tag reads the same from memory as from a file url`() =
    runTest {
      val bytes = rotatedImageProbeBytes(MIRRORED_IMAGE_PROBE_ORIENTATION, bigEndian = false)
      val file = temporaryDirectory.resolve("mirrored.jpg").apply { writeBytes(bytes) }

      val inMemory = measureImage(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<ImageMeasurement.Measured>()
      val byUrl = measureImage(ImageSource.ofUri("file://${file.path}")).shouldBeInstanceOf<ImageMeasurement.Measured>()

      inMemory.exifOrientation shouldBe MIRRORED_IMAGE_PROBE_ORIENTATION
      byUrl.exifOrientation shouldBe MIRRORED_IMAGE_PROBE_ORIENTATION
      byUrl.size shouldBe inMemory.size
    }

  @Test
  fun `a png with a half transparent region reports alpha and an opaque bitmap does not`() =
    runTest {
      val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
      image.setRGB(0, 0, 0x80FF0000.toInt())
      val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
      val file = temporaryDirectory.resolve("half.png").apply { writeBytes(bytes) }

      val inMemory = measureImage(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<ImageMeasurement.Measured>()
      val onDisk = measureImage(ImageSource.of(file.path)).shouldBeInstanceOf<ImageMeasurement.Measured>()
      val opaque = measureImage(ImageSource.ofBytes(imageProbeBytes())).shouldBeInstanceOf<ImageMeasurement.Measured>()

      inMemory.hasAlpha shouldBe true
      onDisk.hasAlpha shouldBe true
      opaque.hasAlpha shouldBe false
    }

  @Test
  fun `a missing file or a directory is unreadable`() =
    runTest {
      measureImage(
        ImageSource.of(temporaryDirectory.resolve("absent.jpg").path),
      ).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
      measureImage(ImageSource.of(temporaryDirectory.path)).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }

  // The desktop has no content resolver, so only a URI naming a file can be opened.
  @Test
  fun `a uri that names no file is unreadable`() =
    runTest {
      measureImage(
        ImageSource.ofUri("content://media/external/images/1"),
      ).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
      measureImage(ImageSource.ofUri("https://example.com/still.jpg")).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }

  @Test
  fun `bytes no reader recognises are unreadable`() =
    runTest {
      measureImage(ImageSource.ofBytes(ByteArray(64) { it.toByte() })).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }
}
