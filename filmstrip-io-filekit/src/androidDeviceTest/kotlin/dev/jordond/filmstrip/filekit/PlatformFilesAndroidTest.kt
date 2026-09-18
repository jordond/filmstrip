package dev.jordond.filmstrip.filekit

import android.net.Uri
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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

    assertEquals(MediaSource.Path(file.absolutePath), picked.toMediaSource())
    assertEquals(ImageSource.Path(file.absolutePath), picked.toImageSource())
    assertEquals(MediaSink.Path(file.absolutePath), picked.toMediaSink())
  }

  @Test
  fun uriWrapperReadsAsUriSource() {
    val picked = PlatformFile(AndroidFile.UriWrapper(contentUri))

    assertEquals(MediaSource.Uri(CONTENT), picked.toMediaSource())
    assertEquals(ImageSource.Uri(CONTENT), picked.toImageSource())
    assertEquals(MediaSink.Uri(CONTENT), picked.toMediaSink())
  }

  @Test
  fun pathSinkComesBackAsAFile() {
    val wrapper = assertIs<AndroidFile.FileWrapper>(MediaSink.of(file.path).toPlatformFile().androidFile)

    assertEquals(file.path, wrapper.file.path)
  }

  @Test
  fun contentUriSinkComesBackAsAUri() {
    val wrapper = assertIs<AndroidFile.UriWrapper>(MediaSink.ofUri(CONTENT).toPlatformFile().androidFile)

    assertEquals(contentUri, wrapper.uri)
  }

  @Test
  fun temporarySinkHasNoFile() {
    assertFailsWith<IllegalStateException> { MediaSink.temporary().toPlatformFile() }
  }

  private companion object {
    const val CONTENT = "content://media/external/video/media/42"
  }
}
