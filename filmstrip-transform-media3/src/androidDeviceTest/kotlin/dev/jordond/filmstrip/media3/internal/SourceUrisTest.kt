package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.internal.AndroidScratch
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * The URI media3 opens each kind of source through, with in-memory sources found in or written to the cache.
 *
 * On a device because every answer is Android's own `Uri`, which the host stubs do not answer.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a method name.
 */
class SourceUrisTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

  @BeforeTest
  fun install() {
    FilmstripContext.install(context)
  }

  @Test
  fun aPathOpensAsAFileUriAndAUriAsItself() {
    val path = File(context.cacheDir, "clip.mp4").path

    MediaSource.of(path).toAndroidUri().path shouldBe path
    MediaSource.of(path).toAndroidUri().scheme shouldBe "file"
    MediaSource.ofUri(CONTENT).toAndroidUri().toString() shouldBe CONTENT
    ImageSource.of(path).toAndroidUri(format = null).path shouldBe path
    ImageSource.ofUri(CONTENT).toAndroidUri(format = null).toString() shouldBe CONTENT
  }

  // The probe wrote the bytes under a name taken from them, so lowering finds that file rather than writing another.
  @Test
  fun inMemoryVideoOpensFromTheFileTheCacheKeepsForIt() {
    val source = MediaSource.Bytes(BYTES)

    val uri = source.toAndroidUri()

    uri.path shouldBe AndroidScratch.fileFor(context, source).path
    File(checkNotNull(uri.path)).readBytes().toList() shouldBe BYTES.toList()
    source.toAndroidUri() shouldBe uri
  }

  @Test
  fun anInMemoryStillIsWrittenUnderTheFormatTheProbeNamed() {
    val uri = ImageSource.ofBytes(BYTES).toAndroidUri("png")

    checkNotNull(uri.path) shouldEndWith ".png"
    File(checkNotNull(uri.path)).readBytes().toList() shouldBe BYTES.toList()
  }

  @Test
  fun anInMemoryStillNobodyNamedTheFormatOfIsRefused() {
    val failure = assertFailsWith<Media3LoweringFailure> { ImageSource.ofBytes(BYTES).toAndroidUri(null) }

    failure.reason shouldContain "nothing that read it named the format to write it under"
  }

  @Test
  fun aStillAsAVideoSourceIsRefusedByName() {
    val still = MediaSource.Image(ImageSource.of("/sdcard/still.jpg"), 2.seconds)

    val failure = assertFailsWith<Media3LoweringFailure> { still.toAndroidUri() }

    failure.reason shouldBe "/sdcard/still.jpg is a still, which is not lowered as a plain URI."
  }

  private companion object {
    const val CONTENT = "content://dev.jordond.filmstrip.test/clip.mp4"
    val BYTES = ByteArray(48) { (it * 5 + 1).toByte() }
  }
}
