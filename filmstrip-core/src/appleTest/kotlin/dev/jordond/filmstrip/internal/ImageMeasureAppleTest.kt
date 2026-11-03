package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import dev.jordond.filmstrip.media.probe.MIRRORED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.imageProbeBytes
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test

class ImageMeasureAppleTest {
  // ImageIO names a bitmap com.microsoft.bmp, and only the last component is the name every target shares.
  @Test
  fun aBitmapInMemoryMeasuresToItsBoundsUnderTheTrailingComponentOfItsType() =
    runTest {
      val measured =
        measureImage(
          ImageSource.ofBytes(imageProbeBytes()),
        ).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe EXIF_ORIENTATION_NORMAL
      measured.format shouldBe "bmp"
    }

  @Test
  fun aMirroredJpegOnDiskReportsItsStoredBoundsAndItsTag() =
    runTest {
      val path = write(rotatedImageProbeBytes(MIRRORED_IMAGE_PROBE_ORIENTATION, bigEndian = false))

      val measured = measureImage(ImageSource.of(path)).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe MIRRORED_IMAGE_PROBE_ORIENTATION
      measured.format shouldBe "jpeg"
    }

  @Test
  fun emptyOrUnrecognisedBytesAreUnreadable() =
    runTest {
      measureImage(ImageSource.ofBytes(ByteArray(0))).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
      measureImage(ImageSource.ofBytes(ByteArray(64) { it.toByte() })).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }

  @Test
  fun aFileThatIsNotThereIsUnreadable() =
    runTest {
      val absent = Path(SystemTemporaryDirectory, "filmstrip-absent-${Random.nextLong(0, Long.MAX_VALUE)}.jpg")

      measureImage(ImageSource.of(absent.toString())).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
      measureImage(ImageSource.ofUri("file://$absent")).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }

  private fun write(bytes: ByteArray): String {
    val path = Path(SystemTemporaryDirectory, "filmstrip-measure-${Random.nextLong(0, Long.MAX_VALUE)}.jpg")
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    return path.toString()
  }
}
