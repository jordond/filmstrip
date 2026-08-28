package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.DestinationResult
import dev.jordond.filmstrip.avfoundation.internal.resolveDestination
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AvDestinationTest {
  @Test
  fun `takes a path whose parent directory exists`() {
    val path = NSTemporaryDirectory() + "filmstrip-destination.mp4"
    val ready = assertIs<DestinationResult.Ready>(resolveDestination(MediaSink.of(path)))

    ready.destination.path shouldBe path
    ready.destination.sink shouldBe MediaSink.Path(path)
  }

  @Test
  fun `refuses a path whose parent directory does not exist`() {
    val failed =
      assertIs<DestinationResult.Failed>(
        resolveDestination(MediaSink.of(NSTemporaryDirectory() + "filmstrip-absent/out.mp4")),
      )

    assertIs<ExportError.SinkUnwritable>(failed.error)
  }

  @Test
  fun `resolves a temporary sink to a real path under the temporary directory`() {
    val ready = assertIs<DestinationResult.Ready>(resolveDestination(MediaSink.Temporary))

    assertTrue(ready.destination.path.startsWith(NSTemporaryDirectory()), ready.destination.path)
    assertIs<MediaSink.Path>(ready.destination.sink)
  }

  @Test
  fun `takes a file URL and refuses every other scheme`() {
    val path = NSTemporaryDirectory() + "filmstrip-url.mp4"
    val fileUrl = NSURL.fileURLWithPath(path).absoluteString!!
    val ready = assertIs<DestinationResult.Ready>(resolveDestination(MediaSink.ofUri(fileUrl)))
    ready.destination.path shouldBe path

    val refused = assertIs<DestinationResult.Failed>(resolveDestination(MediaSink.ofUri("https://example.com/out.mp4")))
    assertIs<ExportError.SinkUnwritable>(refused.error)
  }

  // Everything at the path belongs to the run once the writer opens it, so an abandoned export
  // leaves nothing behind.
  @Test
  fun `discards unconditionally`() {
    val path = NSTemporaryDirectory() + "filmstrip-discard.mp4"
    NSString
      .create(string = "not a video")
      .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    NSFileManager.defaultManager.fileExistsAtPath(path) shouldBe true

    val ready = assertIs<DestinationResult.Ready>(resolveDestination(MediaSink.of(path)))
    ready.destination.discard()

    NSFileManager.defaultManager.fileExistsAtPath(path) shouldBe false
  }
}
