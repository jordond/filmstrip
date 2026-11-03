package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_DURATION
import dev.jordond.filmstrip.media.probe.MIRRORED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.expectedImageInfo
import dev.jordond.filmstrip.media.probe.expectedRotatedImageInfo
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
import kotlin.time.Duration.Companion.seconds

/**
 * ImageIO answers the bounds and the orientation out of a still's metadata, ahead of the asset read
 * that reports no tracks at all for one.
 */
class ImageProbeAppleTest {
  @Test
  fun aStillOnDiskProbesToItsOwnBoundsAndItsDeclaredLength() =
    runTest {
      val path = writeProbeImage()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(path), IMAGE_PROBE_DURATION))

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(FORMAT)
    }

  @Test
  fun aStillInMemoryProbesTheSameAsOneOnDisk() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(imageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(FORMAT)
    }

  @Test
  fun aFileUrlNamesTheSameStillAPathDoes() =
    runTest {
      val path = writeProbeImage()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.ofUri("file://$path"), IMAGE_PROBE_DURATION))

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(FORMAT)
    }

  // A phone stores a photo taken in portrait landscape, with a tag saying which way up it goes, so
  // the bounds reported have to be the stored ones and the turn has to come off the tag.
  @Test
  fun aStillStoredSidewaysReportsItsStoredBoundsAndTheTurnItsTagAsksFor() =
    runTest {
      val path = writeProbeImage("sideways", rotatedImageProbeBytes())

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(path), IMAGE_PROBE_DURATION))

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
    }

  @Test
  fun aSidewaysStillInMemoryReadsTheSameTagOneOnDiskDoes() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(rotatedImageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
    }

  // Transpose mirrors as well as turning. Filmstrip carries no mirror, so it has to report the turn
  // it shares with its unmirrored twin rather than no turn at all.
  @Test
  fun aMirroredStillReportsTheTurnItSharesWithItsUnmirroredTwin() =
    runTest {
      val bytes = rotatedImageProbeBytes(MIRRORED_IMAGE_PROBE_ORIENTATION)
      val source = MediaSource.Image(ImageSource.ofBytes(bytes), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe
        expectedRotatedImageInfo(JPEG, MIRRORED_IMAGE_PROBE_ORIENTATION)
    }

  // The length is the source's, not the file's, so two clips of the same photo report two lengths.
  @Test
  fun theLengthReportedIsTheOneTheSourceDeclares() =
    runTest {
      val bytes = imageProbeBytes()

      val short = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 2.seconds))
      val long = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 11.seconds))

      short.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe 2.seconds
      long.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe 11.seconds
    }

  @Test
  fun aStillThatIsNotThereFailsRatherThanReportingAZeroSizedFrame() =
    runTest {
      val missing = Path(SystemTemporaryDirectory, "filmstrip-absent.bmp").toString()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(missing), IMAGE_PROBE_DURATION))

      val failure = result.shouldBeInstanceOf<ProbeResult.Failure>()
      failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().source shouldBe missing
    }

  @Test
  fun bytesThatAreNotAnImageAtAllFail() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION)

      PlatformProber().probe(source).shouldBeInstanceOf<ProbeResult.Failure>()
    }

  private fun writeProbeImage(
    name: String = "probe",
    bytes: ByteArray = imageProbeBytes(),
  ): String {
    val path = Path(SystemTemporaryDirectory, "filmstrip-$name-${Random.nextLong(0, Long.MAX_VALUE)}")
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
    return path.toString()
  }

  private companion object {
    /**
     * The trailing component of `com.microsoft.bmp`, which is what ImageIO calls an uncompressed
     * bitmap.
     */
    const val FORMAT = "bmp"

    /**
     * The trailing component of `public.jpeg`, the still the rotated fixture is built on.
     */
    const val JPEG = "jpeg"
  }
}
