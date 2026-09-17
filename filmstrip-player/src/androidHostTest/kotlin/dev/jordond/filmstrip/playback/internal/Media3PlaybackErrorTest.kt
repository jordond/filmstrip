package dev.jordond.filmstrip.playback.internal

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import dev.jordond.filmstrip.player.PlaybackError
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The description a [PlaybackException] leaves on the [PlaybackError] it is classified as.
 *
 * ExoPlayer gives a failure a fixed message naming the stage it happened in, like "Source error", and puts what went
 * wrong in the cause under it. A preview that reported only the message named the stage and nothing else.
 *
 * A [PlaybackException] stamps itself with `SystemClock.elapsedRealtime()`, which the host's android.jar does not
 * implement, so the clock is stubbed out.
 */
class Media3PlaybackErrorTest {
  @BeforeTest
  fun stubClock() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
  }

  @AfterTest
  fun releaseClock() {
    unmockkAll()
  }

  @Test
  fun `a source failure keeps the cause under the stage name`() {
    val failure =
      PlaybackException(
        SOURCE_ERROR,
        IOException("Unable to connect to the source"),
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      )

    val error = failure.toPlaybackError()

    error.shouldBeInstanceOf<PlaybackError.SourceUnreadable>().message shouldBe
      "$SOURCE_ERROR: Unable to connect to the source"
  }

  @Test
  fun `every cause down the chain is named`() {
    val chain = IllegalStateException("decoder released", IllegalStateException("surface lost"))
    val failure = PlaybackException(SOURCE_ERROR, chain, PlaybackException.ERROR_CODE_DECODING_FAILED)

    val error = failure.toPlaybackError()

    error.shouldBeInstanceOf<PlaybackError.DecoderUnavailable>().message shouldBe
      "$SOURCE_ERROR: decoder released: surface lost"
  }

  @Test
  fun `a failure carrying no message anywhere falls back to the code's name`() {
    val failure = PlaybackException(null, null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    val error = failure.toPlaybackError()

    error.shouldBeInstanceOf<PlaybackError.DecoderUnavailable>().message shouldBe failure.errorCodeName
  }

  @Test
  fun `the code still decides what the failure is classified as`() {
    val failure =
      PlaybackException(
        SOURCE_ERROR,
        IllegalStateException("effect graph gone"),
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
      )

    val error = failure.toPlaybackError()

    val underlying = error.shouldBeInstanceOf<PlaybackError.Underlying>()
    underlying.platformCode shouldBe PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
    underlying.message shouldBe "$SOURCE_ERROR: effect graph gone"
  }

  private companion object {
    const val SOURCE_ERROR = "Source error"
  }
}
