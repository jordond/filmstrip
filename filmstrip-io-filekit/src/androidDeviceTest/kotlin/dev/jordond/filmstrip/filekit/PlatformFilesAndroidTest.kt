package dev.jordond.filmstrip.filekit

import android.net.Uri
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The Android mapping, on a device because `Uri` is a framework type with no host implementation.
 *
 * A picked document is a content uri and a file the app already owns is a path, and media3 reads
 * and writes both, so neither arm is flattened onto the other.
 */
class PlatformFilesAndroidTest {
  private val file = File("/data/local/tmp/filmstrip/clip.mp4")
  private val contentUri = Uri.parse(CONTENT)

  @Test
  fun fileWrapperReadsAsPathSource() {
    val picked = PlatformFile(AndroidFile.FileWrapper(file))

    picked.toMediaSource() shouldBe MediaSource.Path(file.absolutePath)
    picked.toImageSource() shouldBe ImageSource.Path(file.absolutePath)
    picked.toMediaSink() shouldBe MediaSink.Path(file.absolutePath)
  }

  @Test
  fun uriWrapperReadsAsUriSource() {
    val picked = PlatformFile(AndroidFile.UriWrapper(contentUri))

    picked.toMediaSource() shouldBe MediaSource.Uri(CONTENT)
    picked.toImageSource() shouldBe ImageSource.Uri(CONTENT)
    picked.toMediaSink() shouldBe MediaSink.Uri(CONTENT)
  }

  @Test
  fun pathSinkComesBackAsAFile() {
    val wrapper =
      MediaSink
        .of(file.path)
        .toPlatformFile()
        .androidFile
        .shouldBeInstanceOf<AndroidFile.FileWrapper>()

    wrapper.file.path shouldBe file.path
  }

  @Test
  fun contentUriSinkComesBackAsAUri() {
    val wrapper =
      MediaSink
        .ofUri(CONTENT)
        .toPlatformFile()
        .androidFile
        .shouldBeInstanceOf<AndroidFile.UriWrapper>()

    wrapper.uri shouldBe contentUri
  }

  @Test
  fun temporarySinkHasNoFile() {
    assertFailsWith<IllegalStateException> { MediaSink.temporary().toPlatformFile() }
  }

  private companion object {
    const val CONTENT = "content://media/external/video/media/42"
  }
}
