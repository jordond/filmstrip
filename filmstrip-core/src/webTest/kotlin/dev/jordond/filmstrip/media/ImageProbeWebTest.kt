@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.PlatformProber
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

      // Raw bytes name no media type, so nothing here claims one.
      assertEquals(expectedImageInfo(""), assertIs<ProbeResult.Success>(result).info)
    }

  @Test
  fun anObjectUrlProbesTheSameAndCarriesTheTypeItWasMintedWith() =
    runTest {
      val url = URL.createObjectURL(blobOf(imageProbeBytes(), "image/bmp"))

      try {
        val result = PlatformProber().probe(MediaSource.Image(ImageSource.ofUri(url), IMAGE_PROBE_DURATION))

        assertEquals(expectedImageInfo("bmp"), assertIs<ProbeResult.Success>(result).info)
      } finally {
        URL.revokeObjectURL(url)
      }
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
  fun bytesThatAreNotAnImageAtAllFail() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION)

      assertIs<ProbeResult.Failure>(PlatformProber().probe(source))
    }

  @Test
  fun aFilesystemPathNamesNothingABrowserCanReach() =
    runTest {
      val source = MediaSource.Image(ImageSource.of("/photos/beach.bmp"), IMAGE_PROBE_DURATION)

      assertIs<ProbeResult.Failure>(PlatformProber().probe(source))
    }

  // Everything that is not a still still declines by name here, because reading a container in a
  // browser needs the demuxer the webcodecs module carries.
  @Test
  fun aVideoSourceStillDeclinesAndSaysWhy() =
    runTest {
      val result = PlatformProber().probe(MediaSource.ofUri("https://example.test/clip.mp4"))

      val failure = assertIs<ProbeResult.Failure>(result)
      assertTrue(failure.error.message.contains("demuxer"), failure.error.message)
    }
}
