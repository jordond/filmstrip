package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.media.MediaProber
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Every driver in a process reads one encoder capability answer.
 *
 * Two separately constructed drivers hand back the same instance, which is what says the
 * VideoToolbox probe ran once rather than once per driver.
 */
class AvEncoderCacheTest {
  @Test
  fun `two drivers share one capability read`() =
    runTest(timeout = TIMEOUT) {
      val first = AvDriver(UNUSED_PROBER).capabilities()
      val second = AvDriver(UNUSED_PROBER).capabilities()

      withClue("each driver probed the encoders for itself") { second shouldBeSameInstanceAs first }
    }

  private companion object {
    val TIMEOUT = 5.minutes

    // Reading the capabilities never touches a source, so the driver only needs a prober to exist.
    val UNUSED_PROBER = MediaProber { error("the encoder probe must not read a source") }
  }
}
