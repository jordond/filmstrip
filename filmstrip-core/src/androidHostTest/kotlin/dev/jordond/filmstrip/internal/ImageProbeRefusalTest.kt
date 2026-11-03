package dev.jordond.filmstrip.internal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import java.io.FileNotFoundException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What a probe of a `content://` image reports when the content resolver refuses to open it.
 *
 * The video probe passes the resolver's own words through, so an image read through the same resolver has to as well.
 */
class ImageProbeRefusalTest {
  private val resolver = mockk<ContentResolver>()
  private val parsed = mockk<Uri>()

  @BeforeTest
  fun installAContext() {
    mockkStatic(Uri::class)
    every { Uri.parse(CONTENT) } returns parsed
    FilmstripContext.install(
      mockk<Context> {
        every { applicationContext } returns this
        every { contentResolver } returns resolver
      },
    )
  }

  @AfterTest
  fun cleanUp() {
    FilmstripContext.clear()
    unmockkStatic(Uri::class)
  }

  @Test
  fun `an image the app was not granted reports the denial`() =
    runTest {
      every { resolver.openInputStream(parsed) } throws SecurityException(DENIAL)

      reasonFor(probe()) shouldBe DENIAL
    }

  @Test
  fun `a denial with no words of its own says reading was denied`() =
    runTest {
      every { resolver.openInputStream(parsed) } throws SecurityException()

      reasonFor(probe()) shouldBe "Reading the image was denied."
    }

  @Test
  fun `an image the provider cannot open reports the provider's reason`() =
    runTest {
      every { resolver.openInputStream(parsed) } throws FileNotFoundException(NO_PROVIDER)

      reasonFor(probe()) shouldBe NO_PROVIDER
    }

  @Test
  fun `a provider that hands back no stream reads as unreadable`() =
    runTest {
      every { resolver.openInputStream(parsed) } returns null

      reasonFor(probe()) shouldBe UNREADABLE
    }

  private suspend fun probe(): ProbeResult =
    PlatformProber().probe(MediaSource.Image(ImageSource.ofUri(CONTENT), duration = 2.seconds))

  private fun reasonFor(result: ProbeResult): String {
    val refusal =
      result
        .shouldBeInstanceOf<ProbeResult.Failure>()
        .error
        .shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    refusal.source shouldBe CONTENT
    return refusal.message
  }
}

private const val CONTENT = "content://media/external/images/1"

private const val DENIAL = "Permission Denial: reading $CONTENT requires android.permission.READ_MEDIA_IMAGES"

private const val NO_PROVIDER = "No content provider: $CONTENT"
