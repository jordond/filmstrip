package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.probe.expectedRotatedImageInfo
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * [probeImage] on the three targets that measure a still off the host, where garbage bytes and a missing file both
 * have to come back as a typed failure rather than a throw.
 */
class ProbeImageTest {
  @Test
  fun aStillProbesToItsHeaderAndTheLengthTheSourceDeclares() =
    runTest {
      val result = probeImage(MediaSource.Image(ImageSource.ofBytes(rotatedImageProbeBytes()), 5.seconds))

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe expectedRotatedImageInfo(JPEG)
    }

  @Test
  fun bytesNoReaderOpensFailAsAnUnreadableSourceNamingIt() =
    runTest {
      val source = MediaSource.Image(ImageSource.ofBytes(ByteArray(64) { it.toByte() }), 5.seconds)

      val result = probeImage(source)

      val unreadable =
        result
          .shouldBeInstanceOf<ProbeResult.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      unreadable.source shouldBe source.describe()
      withClue(unreadable.message) { ("could not be opened" in unreadable.message).shouldBeTrue() }
    }

  @Test
  fun anEmptyStillFailsAsAnUnreadableSource() =
    runTest {
      val result = probeImage(MediaSource.Image(ImageSource.ofBytes(ByteArray(0)), 5.seconds))

      result.shouldBeInstanceOf<ProbeResult.Failure>().error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    }

  private companion object {
    const val JPEG = "jpeg"
  }
}
