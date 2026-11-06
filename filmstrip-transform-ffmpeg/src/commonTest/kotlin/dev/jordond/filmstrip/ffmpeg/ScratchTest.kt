package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.process.NOT_A_FILE_URL
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import dev.jordond.filmstrip.ffmpeg.internal.process.scratchFailure
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What the export directory writes, what it writes only once, the paths it hands ffmpeg for an
 * overlay image and for the output file, and what a failure to write it is reported as.
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

    second shouldNotBe first
  }

  @Test
  fun `moves the graph into a file and leaves every other argument where it was`() {
    val command = listOf("ffmpeg", "-i", "a.mp4", "-filter_complex", GRAPH, "-map", "[v]", "out.mp4")

    val spawned = command.readingGraphFrom(scratch)

    spawned shouldBe listOf("ffmpeg", "-i", "a.mp4", "-/filter_complex", spawned[4], "-map", "[v]", "out.mp4")
    SystemFileSystem.source(Path(spawned[4])).buffered().use { it.readString() } shouldBe GRAPH
  }

  @Test
  fun `deletes the graph with the rest of the directory`() {
    val spawned = listOf("-filter_complex", GRAPH).readingGraphFrom(scratch)

    scratch.delete()

    SystemFileSystem.exists(Path(spawned[1])) shouldBe false
  }

  @Test
  fun `leaves a command with no graph as it is`() {
    val copy = listOf("ffmpeg", "-i", "-filter_complex", "-c", "copy", "out.mp4")

    copy.readingGraphFrom(scratch) shouldBe copy
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
    sinkPathOf(MediaSink.ofUri("file:///exports/my%20holiday.mp4")) shouldBe "/exports/my holiday.mp4"
  }

  @Test
  fun `resolves a sink path as it came in`() {
    sinkPathOf(MediaSink.of("/exports/my holiday.mp4")) shouldBe "/exports/my holiday.mp4"
    sinkPathOf(MediaSink.ofUri("/exports/my holiday.mp4")) shouldBe "/exports/my holiday.mp4"
  }

  // Stripping the scheme off this would hand ffmpeg "host/one.mp4", a relative path in whatever
  // directory the process happens to be in, and the export would land somewhere nobody asked for.
  @Test
  fun `refuses a sink uri naming another host rather than turning it into a relative path`() {
    refusalOf(MediaSink.ofUri("file://host/exports/one.mp4")).sink shouldBe "file://host/exports/one.mp4"
  }

  @Test
  fun `refuses a sink uri that names no file`() {
    val refusal = refusalOf(MediaSink.ofUri("https://example.com/out.mp4"))

    refusal.sink shouldBe "https://example.com/out.mp4"
    refusal.message shouldBe NOT_A_FILE_URL
  }

  // An image is handed to ffmpeg as a URL when it is not a local file, and there is nothing on disk to look for.
  @Test
  fun `resolves a remote picture as an input nothing looks for on disk`() {
    val input = scratch.resolve(InputSource.OfImage(ImageSource.ofUri("http://example.com/logo.png")))

    input.path shouldBe "http://example.com/logo.png"
    input.source shouldBe null
  }

  @Test
  fun `resolves a clip as the source the caller named it by`() {
    val input = scratch.resolve(InputSource.OfPath("/clips/my holiday.mp4", "file:///clips/my%20holiday.mp4"))

    input.path shouldBe "/clips/my holiday.mp4"
    input.source shouldBe "file:///clips/my%20holiday.mp4"
  }

  @Test
  fun `a full disk under the scratch directory is insufficient storage`() {
    val failure = scratchFailure(IOException("/tmp/filmstrip-1/asset0.png (No space left on device)"))

    failure.shouldBeInstanceOf<FilmstripError.InsufficientStorage>().message shouldBe
      "/tmp/filmstrip-1/asset0.png (No space left on device)"
  }

  @Test
  fun `any other scratch failure is underlying with no platform code`() {
    val failure = scratchFailure(IOException("Read-only file system")).shouldBeInstanceOf<FilmstripError.Underlying>()

    failure.platformCode shouldBe FilmstripError.Underlying.NO_PLATFORM_CODE
    failure.message shouldBe "Read-only file system"
  }

  private fun sinkPathOf(sink: MediaSink): String =
    Scratch.resolveSink(sink).shouldBeInstanceOf<Scratch.SinkResult.Ready>().path

  private fun refusalOf(sink: MediaSink): FilmstripError.SinkUnwritable =
    Scratch
      .resolveSink(
        sink,
      ).shouldBeInstanceOf<Scratch.SinkResult.Refused>()
      .error
      .shouldBeInstanceOf<FilmstripError.SinkUnwritable>()

  private companion object {
    const val CUBE = "LUT_3D_SIZE 2"
    const val GRAPH = "[0:v]null[v]"
  }
}
