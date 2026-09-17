package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.AvFoundationDriver
import dev.jordond.filmstrip.media.MediaProber
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame
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
      val first = AvFoundationDriver(UNUSED_PROBER).capabilities()
      val second = AvFoundationDriver(UNUSED_PROBER).capabilities()

      assertSame(first, second, "each driver probed the encoders for itself")
    }

  private companion object {
    val TIMEOUT = 5.minutes

    // Reading the capabilities never touches a source, so the driver only needs a prober to exist.
    val UNUSED_PROBER = MediaProber { error("the encoder probe must not read a source") }
  }
}
