package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_DURATION
import dev.jordond.filmstrip.media.probe.expectedImageInfo
import dev.jordond.filmstrip.media.probe.expectedRotatedImageInfo
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Android answers the bounds and the orientation through two platform readers rather than the shared
 * header read, so this is what says it still reports the same thing for the same bytes as the three
 * targets that read them themselves.
 *
 * On a device, because `BitmapFactory` and `ExifInterface` are both stubs off one. A JPEG rather than
 * the bitmap the other targets measure, because Android's decoder is the one of the four that does
 * not open an uncompressed bitmap at all.
 */
class ImageProbeAndroidTest {
  @Test
  fun aStillInMemoryProbesToItsOwnBoundsAndItsDeclaredLength() =
    runTest {
      val bytes = rotatedImageProbeBytes(EXIF_ORIENTATION_NORMAL)
      val source = MediaSource.Image(ImageSource.ofBytes(bytes), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      assertEquals(expectedImageInfo(JPEG), assertIs<ProbeResult.Success>(result).info)
    }

  // A phone stores a photo taken in portrait landscape, with a tag saying which way up it goes, so
  // the bounds reported have to be the stored ones and the turn has to come off the tag.
  @Test
  fun aStillStoredSidewaysReportsItsStoredBoundsAndTheTurnItsTagAsksFor() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(rotatedImageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      assertEquals(expectedRotatedImageInfo(JPEG), assertIs<ProbeResult.Success>(result).info)
    }

  // The length is the source's, not the file's, so two clips of the same photo report two lengths.
  @Test
  fun theLengthReportedIsTheOneTheSourceDeclares() =
    runTest {
      val bytes = rotatedImageProbeBytes()

      val short = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 2.seconds))
      val long = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 11.seconds))

      assertEquals(2.seconds, assertIs<ProbeResult.Success>(short).info.duration)
      assertEquals(11.seconds, assertIs<ProbeResult.Success>(long).info.duration)
    }

  @Test
  fun bytesThatAreNotAnImageAtAllFail() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION)

      assertIs<ProbeResult.Failure>(PlatformProber().probe(source))
    }

  private companion object {
    /**
     * The trailing component of `image/jpeg`, which is what the decoder makes of the header.
     */
    const val JPEG = "jpeg"
  }
}
