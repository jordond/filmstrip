package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.internal.PlatformProber
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.write
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

      assertEquals(expectedImageInfo(FORMAT), assertIs<ProbeResult.Success>(result).info)
    }

  @Test
  fun aStillInMemoryProbesTheSameAsOneOnDisk() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(imageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      assertEquals(expectedImageInfo(FORMAT), assertIs<ProbeResult.Success>(result).info)
    }

  @Test
  fun aFileUrlNamesTheSameStillAPathDoes() =
    runTest {
      val path = writeProbeImage()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.ofUri("file://$path"), IMAGE_PROBE_DURATION))

      assertEquals(expectedImageInfo(FORMAT), assertIs<ProbeResult.Success>(result).info)
    }

  // The length is the source's, not the file's, so two clips of the same photo report two lengths.
  @Test
  fun theLengthReportedIsTheOneTheSourceDeclares() =
    runTest {
      val bytes = imageProbeBytes()

      val short = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 2.seconds))
      val long = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 11.seconds))

      assertEquals(2.seconds, assertIs<ProbeResult.Success>(short).info.duration)
      assertEquals(11.seconds, assertIs<ProbeResult.Success>(long).info.duration)
    }

  @Test
  fun aStillThatIsNotThereFailsRatherThanReportingAZeroSizedFrame() =
    runTest {
      val missing = Path(SystemTemporaryDirectory, "filmstrip-absent.bmp").toString()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(missing), IMAGE_PROBE_DURATION))

      val failure = assertIs<ProbeResult.Failure>(result)
      assertEquals(missing, assertIs<ExportError.SourceUnreadable>(failure.error).source)
    }

  @Test
  fun bytesThatAreNotAnImageAtAllFail() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION)

      assertIs<ProbeResult.Failure>(PlatformProber().probe(source))
    }

  private fun writeProbeImage(): String {
    val path = Path(SystemTemporaryDirectory, "filmstrip-probe-${Random.nextLong(0, Long.MAX_VALUE)}.bmp")
    SystemFileSystem.sink(path).buffered().use { it.write(imageProbeBytes()) }
    return path.toString()
  }

  private companion object {
    /**
     * The trailing component of `com.microsoft.bmp`, which is what ImageIO calls an uncompressed
     * bitmap.
     */
    const val FORMAT = "bmp"
  }
}
