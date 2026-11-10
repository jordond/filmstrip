package dev.jordond.filmstrip.internal

import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.still.StillFormat
import dev.jordond.filmstrip.still.StillWrite
import dev.jordond.filmstrip.still.writeStill
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What each Android reader answers when nothing installed a Context.
 */
class MissingContextTest {
  private val prober = PlatformProber()

  @BeforeTest
  fun forgetTheContext() {
    FilmstripContext.clear()
    mockkStatic(Uri::class)
  }

  @AfterTest
  fun cleanUp() {
    unmockkStatic(Uri::class)
  }

  @Test
  fun `probing a uri video names the missing install`() =
    runTest {
      val refusal =
        failureOf(
          prober.probe(MediaSource.ofUri(CONTENT)),
        ).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

      refusal.source shouldBe CONTENT
      refusal.message shouldBe FilmstripContext.MISSING_CONTEXT
    }

  @Test
  fun `probing an in-memory video names the missing install`() =
    runTest {
      val refusal =
        failureOf(
          prober.probe(MediaSource.Bytes(BYTES)),
        ).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

      refusal.message shouldBe FilmstripContext.MISSING_CONTEXT
    }

  @Test
  fun `probing a uri image names the missing install`() =
    runTest {
      val image = MediaSource.Image(ImageSource.ofUri(CONTENT), duration = 2.seconds)

      val refusal = failureOf(prober.probe(image)).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

      refusal.source shouldBe CONTENT
      refusal.message shouldBe FilmstripContext.MISSING_CONTEXT
    }

  @Test
  fun `a still written to a content uri names the missing install`() =
    runTest {
      val parsed = mockk<Uri> { every { scheme } returns "content" }
      every { Uri.parse(CONTENT) } returns parsed

      val written = writeStill(BYTES, MediaSink.Uri(CONTENT), StillFormat.Png)

      val refusal =
        written
          .shouldBeInstanceOf<StillWrite.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.SinkUnwritable>()
      refusal.sink shouldBe CONTENT
      refusal.message shouldBe FilmstripContext.MISSING_CONTEXT
    }

  @Test
  fun `a temporary still lands in the system temporary directory`() =
    runTest {
      val written = writeStill(BYTES, MediaSink.Temporary, StillFormat.Png).shouldBeInstanceOf<StillWrite.Success>()

      val path = written.output.shouldBeInstanceOf<MediaSink.Path>().path
      try {
        path shouldStartWith File(System.getProperty("java.io.tmpdir").shouldNotBeNull()).path
        File(path).readBytes().toList() shouldBe BYTES.toList()
      } finally {
        File(path).delete()
      }
    }

  private fun failureOf(result: ProbeResult): FilmstripError = result.shouldBeInstanceOf<ProbeResult.Failure>().error
}

private const val CONTENT = "content://media/external/video/1"

private val BYTES = byteArrayOf(1, 2, 3, 4)
