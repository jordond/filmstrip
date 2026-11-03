@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_DURATION
import dev.jordond.filmstrip.media.probe.MIRRORED_IMAGE_PROBE_ORIENTATION
import dev.jordond.filmstrip.media.probe.expectedImageInfo
import dev.jordond.filmstrip.media.probe.expectedRotatedImageInfo
import dev.jordond.filmstrip.media.probe.imageProbeBytes
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A browser is the other target core's own prober declines a container on, because it carries no
 * demuxer. A still needs none, so it is the one source that answers here.
 */
class ImageProbeWebTest {
  @Test
  fun aStillInMemoryProbesToItsOwnBoundsAndItsDeclaredLength() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(imageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      // The type comes off the bytes' own header, so it is the one an object URL of the same bytes
      // carries rather than nothing at all.
      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(BMP)
    }

  @Test
  fun anObjectUrlProbesTheSameAndCarriesTheTypeItWasMintedWith() =
    runTest {
      val url = URL.createObjectURL(blobOf(imageProbeBytes(), "image/bmp"))

      try {
        val result = PlatformProber().probe(MediaSource.Image(ImageSource.ofUri(url), IMAGE_PROBE_DURATION))

        result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(BMP)
      } finally {
        URL.revokeObjectURL(url)
      }
    }

  // A phone stores a photo taken in portrait landscape, with a tag saying which way up it goes, so
  // the bounds reported have to be the stored ones and the turn has to come off the tag.
  @Test
  fun aStillStoredSidewaysReportsItsStoredBoundsAndTheTurnItsTagAsksFor() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(rotatedImageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
    }

  @Test
  fun anObjectUrlOfASidewaysStillReadsTheSameTag() =
    runTest {
      val url = URL.createObjectURL(blobOf(rotatedImageProbeBytes(), "image/jpeg"))

      try {
        val result = PlatformProber().probe(MediaSource.Image(ImageSource.ofUri(url), IMAGE_PROBE_DURATION))

        result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
      } finally {
        URL.revokeObjectURL(url)
      }
    }

  // Transpose mirrors as well as turning, and a browser applying it hands back the same swapped
  // bounds a plain quarter turn does. Pinning it here rather than only over codedSizeOf is what says
  // the decode and the read agree about the mirrored half of the range.
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
  fun bytesThatAreNotAnImageAtAllFail() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION)

      PlatformProber().probe(source).shouldBeInstanceOf<ProbeResult.Failure>()
    }

  @Test
  fun aFilesystemPathNamesNothingABrowserCanReach() =
    runTest {
      val source = MediaSource.Image(ImageSource.of("/photos/beach.bmp"), IMAGE_PROBE_DURATION)

      PlatformProber().probe(source).shouldBeInstanceOf<ProbeResult.Failure>()
    }

  // Everything that is not a still still declines by name here, because reading a container in a
  // browser needs the demuxer the webcodecs module carries.
  @Test
  fun aVideoSourceStillDeclinesAndSaysWhy() =
    runTest {
      val result = PlatformProber().probe(MediaSource.ofUri("https://example.test/clip.mp4"))

      val failure = result.shouldBeInstanceOf<ProbeResult.Failure>()
      withClue(failure.error.message) {
        failure.error.message
          .contains("demuxer")
          .shouldBeTrue()
      }
    }

  private companion object {
    /**
     * What a blob's own media type reduces to for the still both other targets call a bitmap.
     */
    const val BMP = "bmp"

    /**
     * What a blob's own media type reduces to for the still the rotated fixture is built on.
     */
    const val JPEG = "jpeg"
  }
}
