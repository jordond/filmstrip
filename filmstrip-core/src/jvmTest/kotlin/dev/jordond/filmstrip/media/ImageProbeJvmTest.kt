package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.FilmstripError
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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
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

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(FORMAT)
    }

  @Test
  fun `a still in memory probes the same as one on disk`() =
    runTest {
      val result =
        PlatformProber().probe(
          MediaSource.Image(ImageSource.ofBytes(imageProbeBytes()), IMAGE_PROBE_DURATION),
        )

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(FORMAT)
    }

  @Test
  fun `a file url names the same still a path does`() =
    runTest {
      val file = temporaryDirectory.resolve("by-url.bmp").toFile()
      file.writeBytes(imageProbeBytes())

      val result =
        PlatformProber().probe(MediaSource.Image(ImageSource.ofUri("file://${file.path}"), IMAGE_PROBE_DURATION))

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedImageInfo(FORMAT)
    }

  // A phone stores a photo taken in portrait landscape, with a tag saying which way up it goes, so
  // the bounds reported have to be the stored ones and the turn has to come off the tag.
  @Test
  fun `a still stored sideways reports its stored bounds and the turn its tag asks for`() =
    runTest {
      val file = temporaryDirectory.resolve("sideways.jpg").toFile()
      file.writeBytes(rotatedImageProbeBytes())

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(file.path), IMAGE_PROBE_DURATION))

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
    }

  @Test
  fun `a sideways still in memory reads the same tag one on disk does`() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(rotatedImageProbeBytes()), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
    }

  // Transpose mirrors as well as turning. Filmstrip carries no mirror, so it has to report the turn
  // it shares with its unmirrored twin rather than no turn at all.
  @Test
  fun `a mirrored still reports the turn it shares with its unmirrored twin`() =
    runTest {
      val bytes = rotatedImageProbeBytes(MIRRORED_IMAGE_PROBE_ORIENTATION)
      val source = MediaSource.Image(ImageSource.ofBytes(bytes), IMAGE_PROBE_DURATION)

      val result = PlatformProber().probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe
        expectedRotatedImageInfo(JPEG, MIRRORED_IMAGE_PROBE_ORIENTATION)
    }

  // The length is the source's, not the file's, so two clips of the same photo have to report two
  // lengths. Half a second is the middle of nothing in particular, which is the point.
  @Test
  fun `the length reported is the one the source declares`() =
    runTest {
      val bytes = imageProbeBytes()

      val short = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 2.seconds))
      val long = PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(bytes), 11.seconds))

      short.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe 2.seconds
      long.shouldBeInstanceOf<ProbeResult.Success>().info.duration shouldBe 11.seconds
    }

  @Test
  fun `a still that is not there fails rather than reporting a zero-sized frame`() =
    runTest {
      val missing = temporaryDirectory.resolve("absent.bmp").toString()

      val result = PlatformProber().probe(MediaSource.Image(ImageSource.of(missing), IMAGE_PROBE_DURATION))

      val failure = result.shouldBeInstanceOf<ProbeResult.Failure>()
      val error = failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      error.source shouldBe missing
    }

  @Test
  fun `bytes that are not an image at all fail`() =
    runTest {
      val result =
        PlatformProber().probe(MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)), IMAGE_PROBE_DURATION))

      result.shouldBeInstanceOf<ProbeResult.Failure>()
    }

  // Everything that is not a still still declines by name here, because reading a container on the
  // JVM needs the toolchain the ffmpeg module spawns.
  @Test
  fun `a video source still declines and names the artifact to add`() =
    runTest {
      val result = PlatformProber().probe(MediaSource.of("/clips/a.mp4"))

      val failure = result.shouldBeInstanceOf<ProbeResult.Failure>()
      val error = failure.error.shouldBeInstanceOf<FilmstripError.BackendMissing>()
      withClue(error.message) { error.message.contains("ffprobe").shouldBeTrue() }
    }

  private val temporaryDirectory = createTempDirectory("filmstrip-image-probe")

  private companion object {
    /**
     * What the JDK's own reader calls an uncompressed bitmap.
     */
    const val FORMAT = "bmp"

    /**
     * What it calls the still the rotated fixture is built on.
     */
    const val JPEG = "jpeg"
  }
}
