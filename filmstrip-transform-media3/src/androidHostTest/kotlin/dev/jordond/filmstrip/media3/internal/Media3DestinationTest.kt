package dev.jordond.filmstrip.media3.internal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.SINK_NEEDS_FULL_PATH
import dev.jordond.filmstrip.media.SINK_PARENT_MISSING
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Where media3 is pointed for a path or a temporary sink, and how a file written to the cache is moved to a target
 * afterwards.
 *
 * A sink named by URI is parsed by Android's own `Uri`, which the host stubs do not answer, so its routing is checked
 * on a device.
 */
class Media3DestinationTest {
  private val root: File = Files.createTempDirectory("media3-destination").toFile()
  private val resolver = mockk<ContentResolver>()
  private val context =
    mockk<Context> {
      every { cacheDir } returns root
      every { contentResolver } returns resolver
    }

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `a path in a real directory is written to directly`() {
    val path = File(root, "out.mp4").path

    val destination =
      resolveDestination(
        context,
        MediaSink.Path(path),
      ).shouldBeInstanceOf<DestinationResult.Ready>().destination

    destination.path shouldBe path
  }

  @Test
  fun `a path whose directory is missing is refused before media3 starts`() {
    val path = File(root, "missing/out.mp4").path

    val error = resolveDestination(context, MediaSink.Path(path)).shouldBeInstanceOf<DestinationResult.Failed>().error

    val unwritable = error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
    unwritable.sink shouldBe path
    unwritable.message shouldBe SINK_PARENT_MISSING
  }

  @Test
  fun `a path whose parent is a file is refused before media3 starts`() {
    val taken = written("taken")
    val path = File(taken, "out.mp4").path

    val error = resolveDestination(context, MediaSink.Path(path)).shouldBeInstanceOf<DestinationResult.Failed>().error

    error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>().message shouldBe SINK_PARENT_MISSING
  }

  // The host's working directory is writable, so this only passes if the bare name is refused for Android's sake
  // rather than read against wherever the test runs.
  @Test
  fun `a bare file name is refused because an app cannot write to the working directory`() {
    val error =
      resolveDestination(
        context,
        MediaSink.Path("out.mp4"),
      ).shouldBeInstanceOf<DestinationResult.Failed>().error

    val unwritable = error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
    unwritable.sink shouldBe "out.mp4"
    unwritable.message shouldBe SINK_NEEDS_FULL_PATH
  }

  @Test
  fun `a temporary sink is a fresh file in the cache`() {
    val destination =
      resolveDestination(context, MediaSink.temporary()).shouldBeInstanceOf<DestinationResult.Ready>().destination
    val file = File(destination.path)

    file.parentFile shouldBe root
    file.name shouldStartWith "filmstrip-"
    file.name shouldEndWith ".mp4"
    withClue("the scratch file was not created") { file.exists() shouldBe true }
  }

  @Test
  fun `a cache that cannot hold a file fails naming the cache`() {
    val gone = File(root, "gone")
    val missingCache =
      mockk<Context> {
        every { cacheDir } returns gone
      }

    val error =
      resolveDestination(
        missingCache,
        MediaSink.temporary(),
      ).shouldBeInstanceOf<DestinationResult.Failed>().error

    error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>().sink shouldBe gone.path
  }

  @Test
  fun `a path publishes as itself and keeps the file`() {
    val path = written("out.mp4").path
    val destination =
      resolveDestination(
        context,
        MediaSink.Path(path),
      ).shouldBeInstanceOf<DestinationResult.Ready>().destination

    val sink = destination.publish().shouldBeInstanceOf<Media3Destination.PublishResult.Success>().sink

    sink shouldBe MediaSink.Path(path)
    withClue("publishing a path removed the export") { File(path).exists() shouldBe true }
  }

  @Test
  fun `a temporary sink publishes as the scratch file it was written to`() {
    val destination =
      resolveDestination(context, MediaSink.temporary()).shouldBeInstanceOf<DestinationResult.Ready>().destination

    val sink = destination.publish().shouldBeInstanceOf<Media3Destination.PublishResult.Success>().sink

    sink shouldBe MediaSink.Path(destination.path)
    withClue("publishing a temporary sink removed the export") { File(destination.path).exists() shouldBe true }
  }

  @Test
  fun `a target is streamed what media3 wrote and the scratch file is removed`() {
    val scratch = written("scratch.mp4")
    val out = ByteArrayOutputStream()
    every { resolver.openOutputStream(TARGET) } returns out

    val sink = toTarget(scratch).publish().shouldBeInstanceOf<Media3Destination.PublishResult.Success>().sink

    sink shouldBe REPORTED
    out.toByteArray().toList() shouldBe BYTES.toList()
    withClue("the scratch file was left behind") { scratch.exists() shouldBe false }
  }

  @Test
  fun `a provider with no stream to give is unwritable`() {
    val scratch = written("scratch.mp4")
    every { resolver.openOutputStream(TARGET) } returns null

    val error = toTarget(scratch).publish().shouldBeInstanceOf<Media3Destination.PublishResult.Failure>().error

    val unwritable = error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
    unwritable.sink shouldBe TARGET_STRING
    unwritable.message shouldBe "The content provider gave back no stream to write to."
    withClue("the scratch file was left behind") { scratch.exists() shouldBe false }
  }

  @Test
  fun `a stream that fails says why and removes the scratch file`() {
    val scratch = written("scratch.mp4")
    every { resolver.openOutputStream(TARGET) } throws IOException("The volume was unmounted.")

    val error = toTarget(scratch).publish().shouldBeInstanceOf<Media3Destination.PublishResult.Failure>().error

    error.message shouldBe "The volume was unmounted."
    withClue("the scratch file was left behind") { scratch.exists() shouldBe false }
  }

  @Test
  fun `a failure with no message of its own gets one`() {
    every { resolver.openOutputStream(TARGET) } throws IOException()
    toTarget(written("io.mp4")).failure().message shouldBe "The destination could not be written."

    every { resolver.openOutputStream(TARGET) } throws SecurityException()
    toTarget(written("denied.mp4")).failure().message shouldBe "Writing the destination was denied."
  }

  @Test
  fun `a denied write is unwritable rather than a crash`() {
    val scratch = written("scratch.mp4")
    every { resolver.openOutputStream(TARGET) } throws SecurityException("No grant for this uri.")

    val error = toTarget(scratch).failure()

    error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>().sink shouldBe TARGET_STRING
    error.message shouldBe "No grant for this uri."
    withClue("the scratch file was left behind") { scratch.exists() shouldBe false }
  }

  @Test
  fun `discarding removes whatever media3 left`() {
    val scratch = written("partial.mp4")

    toTarget(scratch).discard()

    withClue("discard left the file") { scratch.exists() shouldBe false }
  }

  private fun toTarget(scratch: File): Media3Destination =
    Media3Destination(scratch.path, context, target = TARGET, reported = REPORTED)

  private fun Media3Destination.failure(): FilmstripError =
    publish().shouldBeInstanceOf<Media3Destination.PublishResult.Failure>().error

  private fun written(name: String): File = File(root, name).apply { writeBytes(BYTES) }

  private companion object {
    val TARGET: Uri = mockk()
    val TARGET_STRING = TARGET.toString()
    val REPORTED = MediaSink.Uri("content://media/external/video/media/42")
    val BYTES = ByteArray(4_096) { (it % 251).toByte() }
  }
}
