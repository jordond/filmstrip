package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.internal.platformProber
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Every target's own prober answers a solid with the one answer core works out for it.
 */
class SolidProbeTest {
  @Test
  fun thePlatformProberAnswersASolidWithTheCommonAnswer() =
    runTest {
      val solid = MediaSource.Solid(0xFF336699.toInt(), 3.seconds)

      platformProber().probe(solid).shouldBeInstanceOf<ProbeResult.Success>().info shouldBe solidMediaInfoOf(solid)
    }
}
