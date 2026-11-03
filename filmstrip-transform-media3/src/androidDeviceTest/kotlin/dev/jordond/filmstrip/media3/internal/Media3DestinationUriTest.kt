package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * How a sink named by URI is routed, and the copy out to a target through the real content resolver.
 *
 * On a device because the routing is Android's own `Uri` parsing, which the host stubs do not answer.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a method name.
 */
class Media3DestinationUriTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val root = File(context.cacheDir, "media3-destination-uri").apply { mkdirs() }

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun aFileUriIsWrittenToItsOwnPath() {
    val path = File(root, "out.mp4").path

    val destination = ready(Uri.fromFile(File(path)).toString())

    destination.path shouldBe path
    destination.publish().shouldBeInstanceOf<Media3Destination.PublishResult.Success>().sink shouldBe
      MediaSink.Path(path)
  }

  @Test
  fun aFileUriWhoseDirectoryIsMissingIsRefused() {
    val path = File(root, "missing/out.mp4").path

    val error = failed(Uri.fromFile(File(path)).toString())

    error.sink shouldBe path
    error.message shouldBe "The destination's parent directory does not exist."
  }

  @Test
  fun aFileUriWithNoPathIsRefused() {
    val error = failed("file:")

    error.sink shouldBe "file:"
    error.message shouldBe "The file URI names no path."
  }

  @Test
  fun aContentUriIsWrittenToTheCacheFirst() {
    val destination = ready(CONTENT)

    File(destination.path).parentFile shouldBe context.cacheDir
    withClue("no scratch file was made for the content uri") { File(destination.path).exists() shouldBe true }
    destination.discard()
  }

  @Test
  fun aTargetIsHandedEveryByteThroughTheResolver() {
    val scratch = File(root, "scratch.mp4").apply { writeBytes(BYTES) }
    val target = File(root, "published.mp4")
    val reported = MediaSink.Uri(CONTENT)

    val published = Media3Destination(scratch.path, context, Uri.fromFile(target), reported).publish()

    published.shouldBeInstanceOf<Media3Destination.PublishResult.Success>().sink shouldBe reported
    target.readBytes().toList() shouldBe BYTES.toList()
    withClue("the scratch file was left behind") { scratch.exists() shouldBe false }
  }

  @Test
  fun aProviderThatDoesNotExistIsUnwritable() {
    val scratch = File(root, "scratch.mp4").apply { writeBytes(BYTES) }

    val target = Media3Destination(scratch.path, context, Uri.parse(NO_PROVIDER), MediaSink.Uri(NO_PROVIDER))

    val error = target.publish().shouldBeInstanceOf<Media3Destination.PublishResult.Failure>().error

    error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>().sink shouldBe NO_PROVIDER
    withClue("the scratch file was left behind") { scratch.exists() shouldBe false }
  }

  private fun ready(uri: String): Media3Destination =
    resolveDestination(context, MediaSink.Uri(uri)).shouldBeInstanceOf<DestinationResult.Ready>().destination

  private fun failed(uri: String): FilmstripError.SinkUnwritable =
    resolveDestination(
      context,
      MediaSink.Uri(uri),
    ).shouldBeInstanceOf<DestinationResult.Failed>().error.shouldBeInstanceOf<FilmstripError.SinkUnwritable>()

  private companion object {
    const val CONTENT = "content://media/external/video/media/42"
    const val NO_PROVIDER = "content://dev.jordond.filmstrip.nobody/clip.mp4"
    val BYTES = ByteArray(4_096) { (it % 251).toByte() }
  }
}
