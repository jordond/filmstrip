package dev.jordond.filmstrip.internal

import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import dev.jordond.filmstrip.media.probe.MIRRORED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * `BitmapFactory` reads the bounds and `ExifInterface` the tag, each from a stream of its own, so the two reads have
 * to agree on which source they opened.
 */
class ImageMeasureAndroidTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  init {
    FilmstripContext.install(context)
  }

  @Test
  fun aJpegInMemoryMeasuresToItsBoundsUnderTheNameTheDecoderGivesIt() =
    runTest {
      val measured =
        measureImage(
          ImageSource.ofBytes(rotatedImageProbeBytes(EXIF_ORIENTATION_NORMAL)),
        ).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe EXIF_ORIENTATION_NORMAL
      measured.format shouldBe "jpeg"
    }

  @Test
  fun aMirroredJpegOnDiskReportsItsStoredBoundsAndItsTag() =
    runTest {
      val file = File(context.cacheDir, "measure-mirrored.jpg")
      file.writeBytes(rotatedImageProbeBytes(MIRRORED_IMAGE_PROBE_ORIENTATION, bigEndian = false))

      val measured = measureImage(ImageSource.of(file.path)).shouldBeInstanceOf<ImageMeasurement.Measured>()

      measured.size shouldBe IMAGE_PROBE_SIZE
      measured.exifOrientation shouldBe MIRRORED_IMAGE_PROBE_ORIENTATION
      file.delete()
    }

  @Test
  fun aMissingFileOrADirectoryIsUnreadable() =
    runTest {
      measureImage(
        ImageSource.of(File(context.cacheDir, "measure-absent.jpg").path),
      ).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
      measureImage(ImageSource.of(context.cacheDir.path)).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }

  // A content URI nothing serves is a source this app cannot read, which is an answer rather than a crash, and the
  // resolver's own words say why.
  @Test
  fun aContentUriNoProviderServesIsUnreadableInTheResolversWords() =
    runTest {
      val unread = measureImage(ImageSource.ofUri(ABSENT_PROVIDER)).shouldBeInstanceOf<ImageMeasurement.Unreadable>()

      withClue("the resolver's reason was lost: ${unread.reason}") {
        unread.reason.contains("No content provider").shouldBeTrue()
      }
    }

  @Test
  fun bytesTheDecoderDoesNotRecogniseAreUnreadable() =
    runTest {
      measureImage(ImageSource.ofBytes(ByteArray(64) { it.toByte() })).shouldBeInstanceOf<ImageMeasurement.Unreadable>()
    }
}

private const val ABSENT_PROVIDER = "content://dev.jordond.filmstrip.absent/still.jpg"
