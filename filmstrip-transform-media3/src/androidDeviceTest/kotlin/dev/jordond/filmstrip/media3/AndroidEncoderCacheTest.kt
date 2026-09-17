package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media3.internal.Media3Driver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Every driver in a process reads one encoder capability answer.
 *
 * Two separately constructed drivers hand back the same instance, which is what says the codec list
 * was walked once rather than once per driver. It runs on a device because the host stubs answer
 * nothing for MediaCodecList.
 */
class AndroidEncoderCacheTest {
  @Test
  fun twoDriversShareOneCapabilityRead() =
    runTest {
      val first = Media3Driver(prober = UNUSED_PROBER).capabilities()
      val second = Media3Driver(prober = UNUSED_PROBER).capabilities()

      assertSame(first, second, "each driver probed the encoders for itself")
    }

  private companion object {
    // Reading the capabilities never touches a source, so the driver only needs a prober to exist.
    val UNUSED_PROBER = MediaProber { error("the encoder probe must not read a source") }
  }
}
