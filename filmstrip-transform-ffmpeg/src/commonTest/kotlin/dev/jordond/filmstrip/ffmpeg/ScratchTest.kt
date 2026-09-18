package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.ffmpeg.internal.Scratch
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * What the export directory writes, what it writes only once, and the paths it hands ffmpeg for an
 * overlay image and for the output file.
 */
class ScratchTest {
  private val scratch = Scratch.create()

  @AfterTest
  fun cleanUp() {
    scratch.delete()
  }

  // A graph that reads the same table from two nodes carries the same sidecar twice, and the
  // placeholder it reads them by is one string, so a second write would be a second file nothing
  // ever names.
  @Test
  fun `writes one file for a sidecar that appears twice`() {
    val sidecar = Sidecar(CUBE.encodeToByteArray(), "cube")

    scratch.write(sidecar) shouldBe scratch.write(Sidecar(CUBE.encodeToByteArray(), "cube"))
  }

  @Test
  fun `writes two files for two sidecars`() {
    val first = scratch.write(Sidecar(CUBE.encodeToByteArray(), "cube"))
    val second = scratch.write(Sidecar("LUT_3D_SIZE 4".encodeToByteArray(), "cube"))

    assertNotEquals(first, second)
  }

  // ffmpeg opens the name on disk, so an overlay picked through a file dialog has to arrive as the
  // file it names rather than as the encoding the picker spelled it with. How a file: URL reads is
  // filePathOf's answer, pinned in filmstrip-core.
  @Test
  fun `materialises a file uri as the path it names`() {
    scratch.materialise(ImageSource.ofUri("file:///overlays/my%20logo.png")) shouldBe "/overlays/my logo.png"
  }

  @Test
  fun `materialises a path as it came in`() {
    scratch.materialise(ImageSource.of("/overlays/my logo.png")) shouldBe "/overlays/my logo.png"
    scratch.materialise(ImageSource.ofUri("/overlays/my logo.png")) shouldBe "/overlays/my logo.png"
  }

  // ffmpeg reads http itself, so a URI it can open is handed over whole. Decoding one would name a
  // file that is not there.
  @Test
  fun `materialises a non-file uri as it came in`() {
    scratch.materialise(ImageSource.ofUri("http://example.com/my%20logo.png")) shouldBe
      "http://example.com/my%20logo.png"
  }

  @Test
  fun `resolves a sink uri to the path it names`() {
    Scratch.resolveSink(MediaSink.ofUri("file:///exports/my%20holiday.mp4")) shouldBe "/exports/my holiday.mp4"
  }

  @Test
  fun `resolves a sink path as it came in`() {
    Scratch.resolveSink(MediaSink.of("/exports/my holiday.mp4")) shouldBe "/exports/my holiday.mp4"
    Scratch.resolveSink(MediaSink.ofUri("/exports/my holiday.mp4")) shouldBe "/exports/my holiday.mp4"
  }

  // Stripping the scheme off this would hand ffmpeg "host/one.mp4", a relative path in whatever
  // directory the process happens to be in, and the export would land somewhere nobody asked for.
  @Test
  fun `does not turn a sink uri naming another host into a relative path`() {
    Scratch.resolveSink(MediaSink.ofUri("file://host/exports/one.mp4")) shouldBe "file://host/exports/one.mp4"
  }

  private companion object {
    const val CUBE = "LUT_3D_SIZE 2"
  }
}
