@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.BrowserFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.toJsArray
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The browser mapping, and what releasing one of its sources frees.
 *
 * Whether a URL is still live is asked of the browser rather than of the string, since revoking is
 * the one thing about an object URL that leaves the string looking exactly the same.
 */
class PlatformFilesWebTest {
  @Test
  fun aPickedFileReadsAsAnObjectUrl() =
    runTest {
      val source = pickedFile().toMediaSource()

      assertTrue(assertIs<MediaSource.Uri>(source).uri.startsWith("blob:"), source.uri)
    }

  @Test
  fun aPickedFileReadsAsAnObjectUrlImageToo() =
    runTest {
      val source = pickedFile().toImageSource()

      assertTrue(assertIs<ImageSource.Uri>(source).uri.startsWith("blob:"), source.uri)
    }

  @Test
  fun anObjectUrlIsReadableUntilItIsReleased() =
    runTest {
      val source = pickedFile().toMediaSource()

      assertTrue(source.isReadable(), "a freshly picked file should be readable")
      source.release()
      assertFalse(source.isReadable(), "a released file should no longer be readable")
    }

  @Test
  fun anImageObjectUrlIsReadableUntilItIsReleased() =
    runTest {
      val source = pickedFile().toImageSource()

      assertTrue(source.isReadable(), "a freshly picked still should be readable")
      source.release()
      assertFalse(source.isReadable(), "a released still should no longer be readable")
    }

  // Two picks are two URLs, so releasing one has to leave the other alone.
  @Test
  fun releasingOnePickLeavesAnotherPickReadable() =
    runTest {
      val released = pickedFile("first.mp4").toMediaSource()
      val kept = pickedFile("second.mp4").toMediaSource()

      released.release()

      assertFalse(released.isReadable(), "the released pick should no longer be readable")
      assertTrue(kept.isReadable(), "the other pick should still be readable")
    }

  @Test
  fun releasingAPathDoesNothing() =
    runTest {
      MediaSource.of("/clips/beach.mp4").release()
      ImageSource.of("/stills/beach.png").release()
    }

  @Test
  fun releasingAUriThatIsNotAnObjectUrlDoesNothing() =
    runTest {
      MediaSource.ofUri("https://example.test/clip.mp4").release()
      ImageSource.ofUri("https://example.test/still.png").release()
    }

  @Test
  fun releasingBytesDoesNothing() =
    runTest {
      MediaSource.ofBytes(byteArrayOf(1, 2, 3, 4)).release()
      ImageSource.ofBytes(byteArrayOf(1, 2, 3, 4)).release()
    }

  private suspend fun MediaSource.isReadable(): Boolean = isReadable(assertIs<MediaSource.Uri>(this).uri)

  private suspend fun ImageSource.isReadable(): Boolean = isReadable(assertIs<ImageSource.Uri>(this).uri)

  private suspend fun isReadable(uri: String): Boolean =
    try {
      fetch(uri).await().ok
    } catch (revoked: Throwable) {
      false
    }

  private fun pickedFile(name: String = "clip.mp4"): PlatformFile =
    PlatformFile(WebFile.FileWrapper(BrowserFile(arrayOf<JsAny?>(CONTENT.toJsString()).toJsArray(), name)))

  private companion object {
    /**
     * What the file behind every pick here holds. Nothing reads the bytes, only whether they can
     * still be reached.
     */
    const val CONTENT = "filmstrip"
  }
}

/**
 * Enough of a fetch response to say whether the URL still names anything.
 */
private external interface FetchResponse : JsAny {
  val ok: Boolean
}

private external fun fetch(input: String): Promise<FetchResponse>
