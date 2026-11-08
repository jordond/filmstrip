package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LoopWrapTest {
  private val lastSlot = outputSlotOf(LENGTH, RATE) - 1

  @Test
  fun `a range inside the composition wraps after the last slot it covers`() {
    loopWrapOf(TimeRange.of(ROUNDS_DOWN, 1550.milliseconds), LENGTH, RATE)
      .shouldWrap(at = 1560.milliseconds, firstSlot = 13, lastSlot = 38, reachesEnd = false)
    loopWrapOf(TimeRange.of(ROUNDS_UP, 1530.milliseconds), LENGTH, RATE)
      .shouldWrap(at = 1520.milliseconds, firstSlot = 14, lastSlot = 37, reachesEnd = false)
  }

  // Slot 28 at 30fps is 933.333ms. 920ms rounds up onto it and 945ms rounds down onto it, so both ranges play up to
  // slot 27 and wrap on slot 28's time, one after its own end and one before it.
  @Test
  fun `a range ending inside a slot wraps where the slot after its last one starts`() {
    val next = outputSlotTime(28, VIDEO_RATE)

    loopWrapOf(TimeRange.of(LOOP_FROM, 920.milliseconds), LENGTH, VIDEO_RATE)
      .shouldWrap(at = next, firstSlot = 9, lastSlot = 27, reachesEnd = false)
    loopWrapOf(TimeRange.of(LOOP_FROM, 945.milliseconds), LENGTH, VIDEO_RATE)
      .shouldWrap(at = next, firstSlot = 9, lastSlot = 27, reachesEnd = false)
  }

  @Test
  fun `a range with no end runs to the composition's last slot and reaches its end`() {
    loopWrapOf(TimeRange.from(ROUNDS_UP), LENGTH, RATE)
      .shouldWrap(at = LENGTH, firstSlot = 14, lastSlot = lastSlot, reachesEnd = true)
  }

  @Test
  fun `a range ending on the duration reaches the composition's end`() {
    loopWrapOf(TimeRange.of(ROUNDS_UP, LENGTH), LENGTH, RATE)
      .shouldWrap(at = LENGTH, firstSlot = 14, lastSlot = lastSlot, reachesEnd = true)
  }

  // 3060ms lands on slot 77, so a range whose end was not held to the duration would carry slot 76, one the composition
  // does not have.
  @Test
  fun `a range ending past the duration is held to it`() {
    loopWrapOf(TimeRange.of(ROUNDS_UP, 3060.milliseconds), LENGTH, RATE)
      .shouldWrap(at = LENGTH, firstSlot = 14, lastSlot = lastSlot, reachesEnd = true)
    loopWrapOf(TimeRange.of(ROUNDS_UP, 5.seconds), LENGTH, RATE)
      .shouldWrap(at = LENGTH, firstSlot = 14, lastSlot = lastSlot, reachesEnd = true)
  }

  // 3020ms rounds up onto the slot the duration lands on, so the range plays every slot the composition has. 2990ms
  // rounds up onto the composition's last slot, so that range stops one short and wraps on the last slot's time.
  @Test
  fun `a range ending a fraction short of the duration reaches the end when its last slot is the composition's`() {
    loopWrapOf(TimeRange.of(ROUNDS_UP, 3020.milliseconds), LENGTH, RATE)
      .shouldWrap(at = LENGTH, firstSlot = 14, lastSlot = lastSlot, reachesEnd = true)
    loopWrapOf(TimeRange.of(ROUNDS_UP, 2990.milliseconds), LENGTH, RATE)
      .shouldWrap(at = outputSlotTime(lastSlot, RATE), firstSlot = 14, lastSlot = lastSlot - 1, reachesEnd = false)
  }

  // Both ends land on slot 25, so the range covers no slot of its own.
  @Test
  fun `a range too narrow to cover a slot loops on the slot its start lands on`() {
    loopWrapOf(TimeRange.of(1000.milliseconds, 1010.milliseconds), LENGTH, RATE)
      .shouldWrap(at = 1040.milliseconds, firstSlot = 25, lastSlot = 25, reachesEnd = false)
  }

  @Test
  fun `a range starting at or past the duration has nothing to loop`() {
    loopWrapOf(TimeRange.from(LENGTH), LENGTH, RATE) shouldBe null
    loopWrapOf(TimeRange.of(LENGTH, 4.seconds), LENGTH, RATE) shouldBe null
    loopWrapOf(TimeRange.from(5.seconds), LENGTH, RATE) shouldBe null
  }

  // 3025ms rounds up past the last slot and 3015ms rounds down onto it, both inside the composition.
  @Test
  fun `a range starting inside the last slot loops it only when its start lands on it`() {
    loopWrapOf(TimeRange.from(3025.milliseconds), LENGTH, RATE) shouldBe null
    loopWrapOf(TimeRange.from(3015.milliseconds), LENGTH, RATE)
      .shouldWrap(at = LENGTH, firstSlot = lastSlot, lastSlot = lastSlot, reachesEnd = true)
  }

  // At 30fps a slot's time is rounded down to a whole microsecond, and it still lands on its own slot.
  @Test
  fun `a range ending on a slot's own time stops on the slot before it`() {
    val range = TimeRange.of(outputSlotTime(4, VIDEO_RATE), outputSlotTime(10, VIDEO_RATE))

    loopWrapOf(range, LENGTH, VIDEO_RATE)
      .shouldWrap(at = outputSlotTime(10, VIDEO_RATE), firstSlot = 4, lastSlot = 9, reachesEnd = false)
  }

  @Test
  fun `an empty composition has nothing to loop`() {
    loopWrapOf(TimeRange.from(Duration.ZERO), Duration.ZERO, RATE) shouldBe null
  }

  @Test
  fun `a grid with no positive frame rate is refused`() {
    assertFailsWith<IllegalArgumentException> { loopWrapOf(TimeRange.from(ROUNDS_UP), LENGTH, 0) }
  }

  private fun LoopWrap?.shouldWrap(
    at: Duration,
    firstSlot: Long,
    lastSlot: Long,
    reachesEnd: Boolean,
  ) {
    val wrap = shouldNotBeNull()
    wrap.at shouldBe at
    wrap.firstSlot shouldBe firstSlot
    wrap.lastSlot shouldBe lastSlot
    wrap.reachesEnd shouldBe reachesEnd
  }

  private companion object {
    const val RATE = 25
    const val VIDEO_RATE = 30
    val LENGTH: Duration = 3030.milliseconds

    // Slots 13 and 14 at 25fps are 520ms and 560ms, so these sit a quarter of a slot past one and before the other.
    val ROUNDS_DOWN: Duration = 530.milliseconds
    val ROUNDS_UP: Duration = 550.milliseconds

    // Slot 9 at 30fps.
    val LOOP_FROM: Duration = 300.milliseconds
  }
}
