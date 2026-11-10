package dev.jordond.filmstrip.media3.internal.bounds

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Which access unit [prerollUnitOf] opens a clip's audio decode on, for a trim start inside a unit, on one, and inside
 * the track's first.
 */
class PrerollUnitTest {
  @Test
  fun `a start inside a unit opens on the unit before it`() {
    prerollUnitOf(UNITS, 210_000) shouldBe UNITS[8]
  }

  @Test
  fun `a start on a unit opens on the unit before it`() {
    prerollUnitOf(UNITS, UNITS[9]) shouldBe UNITS[8]
  }

  @Test
  fun `a start inside the first unit opens on that unit`() {
    prerollUnitOf(UNITS, 10_000) shouldBe UNITS[0]
    prerollUnitOf(UNITS, 0) shouldBe UNITS[0]
  }

  @Test
  fun `a start before every unit has none`() {
    prerollUnitOf(UNITS.drop(2), 30_000) shouldBe null
    prerollUnitOf(emptyList(), 30_000) shouldBe null
  }

  private companion object {
    // AAC access units of 1024 frames at 48 kHz, in the whole microseconds a container times them in.
    val UNITS = (0L until 16L).map { it * 1_024 * 1_000_000 / 48_000 }
  }
}
