package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.internal.PlatformProber
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The JVM is one of the two targets core's own prober declines a container on. A still is the one
 * source it answers itself, because ImageIO reads the bounds out of the header and the source
 * declares the length.
 */
class ImageProbeJvmTest {
  @Test
  fun `a still on disk probes to its own bounds and its declared length`() =
    runTest {
      val file = temporaryDirectory.resolve("probe.bmp").toFile()
      file.writeBytes(imageProbeBytes())

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(file.path), IMAGE_PROBE_DURATION))

      assertEquals(expectedImageInfo(FORMAT), assertIs<ProbeResult.Success>(result).info)
    }

  @Test
  fun `a still in memory probes the same as one on disk`() =
    runTest {
      val result =
        PlatformProber().probe(
          MediaSource.Image(ImageSource.ofBytes(imageProbeBytes()), IMAGE_PROBE_DURATION),
        )

      assertEquals(expectedImageInfo(FORMAT), assertIs<ProbeResult.Success>(result).info)
    }

  @Test
  fun `a file url names the same still a path does`() =
    runTest {
      val file = temporaryDirectory.resolve("by-url.bmp").toFile()
      file.writeBytes(imageProbeBytes())

      val result =
        PlatformProber().probe(MediaSource.Image(ImageSource.ofUri("file://${file.path}"), IMAGE_PROBE_DURATION))

      assertEquals(expectedImageInfo(FORMAT), assertIs<ProbeResult.Success>(result).info)
    }

  // The length is the source's, not the file's, so two clips of the same photo have to report two
  // lengths. Half a second is the middle of nothing in particular, which is the point.
  @Test
  fun `the length reported is the one the source declares`() =
    runTest {
      val bytes = imageProbeBytes()

      val short = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 2.seconds))
      val long = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 11.seconds))

      assertEquals(2.seconds, assertIs<ProbeResult.Success>(short).info.duration)
      assertEquals(11.seconds, assertIs<ProbeResult.Success>(long).info.duration)
    }

  @Test
  fun `a still that is not there fails rather than reporting a zero-sized frame`() =
    runTest {
      val missing = temporaryDirectory.resolve("absent.bmp").toString()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(missing), IMAGE_PROBE_DURATION))

      val failure = assertIs<ProbeResult.Failure>(result)
      val error = assertIs<ExportError.SourceUnreadable>(failure.error)
      assertEquals(missing, error.source)
    }

  @Test
  fun `bytes that are not an image at all fail`() =
    runTest {
      val result =
        PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION))

      assertIs<ProbeResult.Failure>(result)
    }

  // Everything that is not a still still declines by name here, because reading a container on the
  // JVM needs the toolchain the ffmpeg module spawns.
  @Test
  fun `a video source still declines and names the artifact to add`() =
    runTest {
      val result = PlatformProber().probe(MediaSource.of("/clips/a.mp4"))

      val failure = assertIs<ProbeResult.Failure>(result)
      val error = assertIs<ExportError.BackendMissing>(failure.error)
      assertTrue(error.message.contains("ffprobe"), error.message)
    }

  private val temporaryDirectory = createTempDirectory("filmstrip-image-probe")

  private companion object {
    /**
     * What the JDK's own reader calls an uncompressed bitmap.
     */
    const val FORMAT = "bmp"
  }
}
