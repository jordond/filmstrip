@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.URL
import dev.jordond.filmstrip.media.blobOf
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import dev.jordond.filmstrip.media.probe.MIRRORED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.imageProbeBytes
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test

class ImageMeasureWebTest {
  // Bytes carry no type of their own, so the name comes off the header the way an object URL's would.
  @Test
  fun aBitmapInMemoryMeasuresToItsBoundsUnderTheTypeReadOffItsBytes() =
    runTest {
      val measured =
        measureImage(
          ImageSource.ofBytes(imageProbeBytes()),
        ).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe EXIF_ORIENTATION_NORMAL
      measured.format shouldBe "bmp"
    }

  // A fetched blob is read in slices rather than copied out whole, so its tag has to land the same as the array's.
  @Test
  fun anObjectUrlReadsTheSameStoredBoundsAndTagAsItsBytes() =
    runTest {
      val bytes = rotatedImageProbeBytes(MIRRORED_IMAGE_PROBE_ORIENTATION, bigEndian = false)
      val url = URL.createObjectURL(blobOf(bytes, "image/jpeg"))

      try {
        val byUrl = measureImage(ImageSource.ofUri(url)).shouldBeInstanceOf<ImageMeasurement.Measured>()
        val inMemory = measureImage(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<ImageMeasurement.Measured>()

        byUrl.size shouldBe IMAGE_PROBE_SIZE
        byUrl.exifOrientation shouldBe MIRRORED_IMAGE_PROBE_ORIENTATION
        byUrl.format shouldBe "jpeg"
        byUrl.size shouldBe inMemory.size
        byUrl.exifOrientation shouldBe inMemory.exifOrientation
      } finally {
        URL.revokeObjectURL(url)
      }
    }

  @Test
  fun aPathOrBytesNoBrowserDecodesAreUnreadable() =
    runTest {
      measureImage(ImageSource.of("/photos/beach.jpg")).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
      measureImage(ImageSource.ofBytes(ByteArray(64) { it.toByte() })).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }
}
