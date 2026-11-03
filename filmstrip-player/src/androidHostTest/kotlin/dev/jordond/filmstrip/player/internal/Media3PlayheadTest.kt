package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the media3 engine reports as its playhead for a reading media3 gives in whole milliseconds.
 *
 * The landings are read off the shared output grid, so the millisecond media3 is handed is whatever [toMediaMs] floors
 * the slot's time to rather than a number copied here.
 */
class Media3PlayheadTest {
  @Test
  fun `a reading of the floored millisecond a seek was handed reports the slot it landed on`() {
    val landing = outputSlotTime(MID_SLOT, RATE)
    val handed = landing.toMediaMs()

    handed.milliseconds shouldBeLessThan landing
    playheadOf(handed, landing) shouldBe landing
  }

  @Test
  fun `a reading either side of that millisecond is reported as media3 gave it`() {
    val landing = outputSlotTime(MID_SLOT, RATE)
    val handed = landing.toMediaMs()

    playheadOf(handed + 1, landing) shouldBe (handed + 1).milliseconds
    playheadOf(handed - 1, landing) shouldBe (handed - 1).milliseconds
  }

  @Test
  fun `a slot on a whole millisecond reports that millisecond`() {
    val landing = outputSlotTime(WHOLE_SLOT, RATE)

    playheadOf(landing.toMediaMs(), landing) shouldBe landing
  }

  @Test
  fun `a reading with no seek behind it is reported as media3 gave it`() {
    playheadOf(READING_MS, landing = null) shouldBe READING_MS.milliseconds
  }

  private companion object {
    const val RATE = 30

    // Two thirds of a millisecond past a whole one at 30 fps, well inside a composition rather than at either end.
    const val MID_SLOT = 32L

    // Thirty slots in at 30 fps is a whole second, which no floor can move.
    const val WHOLE_SLOT = 30L

    const val READING_MS = 1_234L
  }
}
