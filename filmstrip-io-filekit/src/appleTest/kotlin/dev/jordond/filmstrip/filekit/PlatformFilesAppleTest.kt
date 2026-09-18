package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import io.kotest.matchers.shouldBe
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Which arm an Apple URL lands on. A file URL carries a path and a remote one does not, and that is
 * the whole of the mapping.
 */
class PlatformFilesAppleTest {
  private val path = "/tmp/filmstrip/clip.mp4"
  private val fileUrl = NSURL.fileURLWithPath(path)
  private val remoteUrl = NSURL.URLWithString(REMOTE)!!

  @Test
  fun `a file url reads as a path source`() {
    PlatformFile(fileUrl).toMediaSource() shouldBe MediaSource.Path(path)
  }

  @Test
  fun `a file url reads as a path image`() {
    PlatformFile(fileUrl).toImageSource() shouldBe ImageSource.Path(path)
  }

  @Test
  fun `a remote url reads as a uri source`() {
    PlatformFile(remoteUrl).toMediaSource() shouldBe MediaSource.Uri(REMOTE)
  }

  @Test
  fun `a file url writes as a path sink`() {
    PlatformFile(fileUrl).toMediaSink() shouldBe MediaSink.Path(path)
  }

  @Test
  fun `a remote url writes as a uri sink`() {
    PlatformFile(remoteUrl).toMediaSink() shouldBe MediaSink.Uri(REMOTE)
  }

  @Test
  fun `a path sink comes back as a file url`() {
    MediaSink
      .of(path)
      .toPlatformFile()
      .nsUrl.path shouldBe path
  }

  @Test
  fun `a uri sink comes back as the same url`() {
    MediaSink
      .ofUri(REMOTE)
      .toPlatformFile()
      .nsUrl.absoluteString shouldBe REMOTE
  }

  @Test
  fun `a temporary sink has no file`() {
    assertFailsWith<IllegalStateException> { MediaSink.temporary().toPlatformFile() }
  }

  private companion object {
    const val REMOTE = "https://example.com/clip.mp4"
  }
}
