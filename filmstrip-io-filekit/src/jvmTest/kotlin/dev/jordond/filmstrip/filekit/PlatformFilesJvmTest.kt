package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The JVM mapping, in both directions. A desktop picker only ever hands back a file on disk, so
 * every arm here is a path.
 */
class PlatformFilesJvmTest {
  private val file = File("clip.mp4").absoluteFile

  @Test
  fun `a file reads as a path source`() {
    PlatformFile(file).toMediaSource() shouldBe MediaSource.Path(file.absolutePath)
  }

  @Test
  fun `a file reads as a path image`() {
    PlatformFile(file).toImageSource() shouldBe ImageSource.Path(file.absolutePath)
  }

  @Test
  fun `a file writes as a path sink`() {
    PlatformFile(file).toMediaSink() shouldBe MediaSink.Path(file.absolutePath)
  }

  @Test
  fun `a path sink comes back as the same file`() {
    MediaSink
      .of(file.absolutePath)
      .toPlatformFile()
      .file.absolutePath shouldBe file.absolutePath
  }

  @Test
  fun `a file url sink comes back as the same file`() {
    MediaSink
      .ofUri(file.toURI().toString())
      .toPlatformFile()
      .file.absolutePath shouldBe file.absolutePath
  }

  @Test
  fun `a file url sink is percent-decoded`() {
    MediaSink
      .ofUri("file:///tmp/my%20clip.mp4")
      .toPlatformFile()
      .file.path shouldBe "/tmp/my clip.mp4"
  }

  @Test
  fun `a file url sink with a raw space is refused`() {
    assertFailsWith<IllegalArgumentException> {
      MediaSink.ofUri("file:///tmp/my clip.mp4").toPlatformFile()
    }
  }

  @Test
  fun `a non-file url sink is refused`() {
    assertFailsWith<IllegalArgumentException> {
      MediaSink.ofUri("content://media/external/video/media/42").toPlatformFile()
    }
  }

  @Test
  fun `a temporary sink has no file`() {
    assertFailsWith<IllegalStateException> { MediaSink.temporary().toPlatformFile() }
  }
}
