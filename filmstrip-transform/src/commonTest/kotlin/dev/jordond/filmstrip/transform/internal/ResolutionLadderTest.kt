package dev.jordond.filmstrip.transform.internal

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rungs an encoder is probed at, and the one rung the HDR probe uses.
 *
 * Every backend that has to discover a ceiling by asking reads both from here. They were three
 * private copies before, and the two shapes disagreed on which rung was smallest, so the same
 * ten-bit question was asked at 720p on one backend and 480p on the other two.
 */
class ResolutionLadderTest {
  @Test
  fun `the ladder descends so the first rung that opens is the largest that works`() {
    val pixels = RESOLUTION_LADDER.map { it.width.toLong() * it.height }

    pixels shouldBe pixels.sortedDescending()
    assertTrue(pixels.distinct().size == pixels.size, "a repeated rung would be probed twice")
  }

  @Test
  fun `every rung is even on both axes since an encoder rejects an odd dimension`() {
    RESOLUTION_LADDER.forEach { size ->
      assertTrue(size.width % 2 == 0 && size.height % 2 == 0, "$size is odd")
    }
  }

  @Test
  fun `the HDR probe runs at the cheapest rung`() {
    HDR_PROBE_SIZE shouldBe RESOLUTION_LADDER.last()
  }
}
