package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.filePathOf
import io.github.vinceglb.filekit.PlatformFile
import io.kotest.matchers.nulls.shouldNotBeNull
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
    val uri = "file:///tmp/my%20clip.mp4"

    MediaSink
      .ofUri(uri)
      .toPlatformFile()
      .file.path shouldBe filePathOf(uri)
  }

  @Test
  fun `a file url sink is read without regard to the scheme's case`() {
    val uri = "FILE:///tmp/clip.mp4"

    MediaSink
      .ofUri(uri)
      .toPlatformFile()
      .file.path shouldBe filePathOf(uri).shouldNotBeNull()
  }

  @Test
  fun `a file url sink with a raw space is refused`() {
    assertRefused("file:///tmp/my clip.mp4")
  }

  @Test
  fun `a file url sink naming another host is refused`() {
    assertRefused("file://server/share/clip.mp4")
  }

  @Test
  fun `a non-file url sink is refused`() {
    assertRefused("content://media/external/video/media/42")
  }

  @Test
  fun `a temporary sink has no file`() {
    assertFailsWith<IllegalStateException> { MediaSink.temporary().toPlatformFile() }
  }

  // Core's decoder answers null for every one of these, so each is refused the same way.
  private fun assertRefused(uri: String) {
    filePathOf(uri) shouldBe null

    val refusal = assertFailsWith<IllegalArgumentException> { MediaSink.ofUri(uri).toPlatformFile() }

    refusal.message shouldBe "$uri is not a local file: URL."
  }
}
