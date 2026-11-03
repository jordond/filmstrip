package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.writer.DestinationResult
import dev.jordond.filmstrip.avfoundation.internal.writer.resolveDestination
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.SINK_PARENT_MISSING
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AvDestinationTest {
  @Test
  fun `takes a path whose parent directory exists`() {
    val path = NSTemporaryDirectory() + "filmstrip-destination.mp4"
    val ready = resolveDestination(MediaSink.of(path)).shouldBeInstanceOf<DestinationResult.Ready>()

    ready.destination.path shouldBe path
    ready.destination.sink shouldBe MediaSink.Path(path)
  }

  @Test
  fun `refuses a path whose parent directory does not exist`() {
    val failed =
      resolveDestination(
        MediaSink.of(NSTemporaryDirectory() + "filmstrip-absent/out.mp4"),
      ).shouldBeInstanceOf<DestinationResult.Failed>()

    failed.error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>().message shouldBe SINK_PARENT_MISSING
  }

  @Test
  fun `refuses a path whose parent is a file`() {
    val taken = NSTemporaryDirectory() + "filmstrip-destination-taken"
    NSString
      .create(string = "not a directory")
      .writeToFile(taken, atomically = true, encoding = NSUTF8StringEncoding, error = null)

    try {
      val failed = resolveDestination(MediaSink.of("$taken/out.mp4")).shouldBeInstanceOf<DestinationResult.Failed>()

      val unwritable = failed.error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      unwritable.sink shouldBe "$taken/out.mp4"
      unwritable.message shouldBe SINK_PARENT_MISSING
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(taken, error = null)
    }
  }

  @Test
  fun `takes a bare name as a file in the working directory`() {
    val ready = resolveDestination(MediaSink.of("filmstrip-bare.mp4")).shouldBeInstanceOf<DestinationResult.Ready>()

    ready.destination.path shouldBe "filmstrip-bare.mp4"
  }

  @Test
  fun `resolves a temporary sink to a real path under the temporary directory`() {
    val ready = resolveDestination(MediaSink.Temporary).shouldBeInstanceOf<DestinationResult.Ready>()

    ready.destination.path shouldStartWith NSTemporaryDirectory()
    ready.destination.sink.shouldBeInstanceOf<MediaSink.Path>()
  }

  @Test
  fun `takes a file URL and refuses every other scheme`() {
    val path = NSTemporaryDirectory() + "filmstrip-url.mp4"
    val fileUrl = NSURL.fileURLWithPath(path).absoluteString!!
    val ready = resolveDestination(MediaSink.ofUri(fileUrl)).shouldBeInstanceOf<DestinationResult.Ready>()
    ready.destination.path shouldBe path

    val refused =
      resolveDestination(
        MediaSink.ofUri("https://example.com/out.mp4"),
      ).shouldBeInstanceOf<DestinationResult.Failed>()
    refused.error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
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

    val ready = resolveDestination(MediaSink.of(path)).shouldBeInstanceOf<DestinationResult.Ready>()
    ready.destination.discard()

    NSFileManager.defaultManager.fileExistsAtPath(path) shouldBe false
  }
}
