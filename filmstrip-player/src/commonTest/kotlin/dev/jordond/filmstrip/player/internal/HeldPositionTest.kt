package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class HeldPositionTest {
  @Test
  fun `a playhead inside the new composition holds on the slot it rounds to`() {
    heldPosition(slot(45) + THIRD, LENGTH, RATE, null, playing = false) shouldBe slot(45)
    heldPosition(slot(45) - THIRD, LENGTH, RATE, null, playing = true) shouldBe slot(45)
  }

  @Test
  fun `a playing playhead rounding down lands on the slot after it rather than behind itself`() {
    heldPosition(slot(45) + THIRD, LENGTH, RATE, null, playing = true) shouldBe slot(46)
    heldPosition(slot(45), LENGTH, RATE, null, playing = true) shouldBe slot(45)
  }

  @Test
  fun `a playing reading with sub microsecond precision on a slot stays on that slot`() {
    heldPosition(slot(1) + 333.nanoseconds, LENGTH, RATE, null, playing = true) shouldBe slot(1)
    heldPosition(slot(45) + 333.nanoseconds, LENGTH, RATE, null, playing = true) shouldBe slot(45)
  }

  @Test
  fun `a playing playhead inside the last slot of the loop range lands on its first slot`() {
    val range = TimeRange.of(slot(40), slot(60))

    heldPosition(slot(59), LENGTH, RATE, range, playing = true) shouldBe slot(59)
    heldPosition(slot(59) + THIRD, LENGTH, RATE, range, playing = true) shouldBe slot(40)
  }

  @Test
  fun `a playing playhead inside the last slot holds there`() {
    val last = outputSlotOf(LENGTH, RATE) - 1

    heldPosition(slot(last) + THIRD, LENGTH, RATE, null, playing = true) shouldBe slot(last)
    heldPosition(LENGTH - 1.microseconds, LENGTH, RATE, null, playing = true) shouldBe slot(last)
  }

  @Test
  fun `a playing playhead inside the last slot of a duration off the grid holds there`() {
    val offGrid = slot(45) + THIRD
    val last = outputSlotOf(offGrid, RATE) - 1

    heldPosition(slot(last) + THIRD, offGrid, RATE, null, playing = true) shouldBe slot(last)
    heldPosition(offGrid - 1.microseconds, offGrid, RATE, null, playing = true) shouldBe slot(last)
    heldPosition(slot(last - 1) + THIRD, offGrid, RATE, null, playing = true) shouldBe slot(last)
  }

  @Test
  fun `a playhead past a shorter composition holds on its last frame`() {
    val shorter = 1.seconds
    val last = outputSlotOf(shorter, RATE) - 1

    heldPosition(slot(45), shorter, RATE, null, playing = false) shouldBe slot(last)
    heldPosition(shorter, shorter, RATE, null, playing = true) shouldBe slot(last)
  }

  @Test
  fun `a playing playhead before the loop range keeps its own slot`() {
    heldPosition(slot(20), LENGTH, RATE, TimeRange.of(slot(40), slot(60)), playing = true) shouldBe slot(20)
  }

  @Test
  fun `a playing playhead inside the loop range keeps its own slot`() {
    heldPosition(slot(50), LENGTH, RATE, TimeRange.of(slot(40), slot(60)), playing = true) shouldBe slot(50)
  }

  @Test
  fun `a playing playhead past the loop range lands on its first slot`() {
    val range = TimeRange.of(slot(40), slot(60))

    heldPosition(slot(60), LENGTH, RATE, range, playing = true) shouldBe slot(40)
    heldPosition(slot(70), LENGTH, RATE, range, playing = true) shouldBe slot(40)
  }

  @Test
  fun `a playing playhead rounding onto the slot after the loop range lands on its first slot`() {
    val range = TimeRange.of(slot(40), slot(60))

    heldPosition(slot(60) - THIRD, LENGTH, RATE, range, playing = true) shouldBe slot(40)
  }

  @Test
  fun `a playing playhead past a shorter composition the loop range runs off wraps to the range start`() {
    val shorter = 1.seconds

    heldPosition(slot(45), shorter, RATE, TimeRange.from(slot(10)), playing = true) shouldBe slot(10)
  }

  @Test
  fun `a paused playhead past the loop range stays where it lands`() {
    val range = TimeRange.of(slot(40), slot(60))

    heldPosition(slot(70), LENGTH, RATE, range, playing = false) shouldBe slot(70)
    heldPosition(slot(70) + THIRD, LENGTH, RATE, range, playing = false) shouldBe slot(70)
  }

  @Test
  fun `a paused playhead past a shorter composition holds on its last frame under a loop range`() {
    val shorter = 1.seconds
    val last = outputSlotOf(shorter, RATE) - 1

    heldPosition(slot(45), shorter, RATE, TimeRange.from(slot(10)), playing = false) shouldBe slot(last)
  }

  @Test
  fun `a loop range the composition ends before leaves a playing playhead on the last slot`() {
    val shorter = 1.seconds
    val last = outputSlotOf(shorter, RATE) - 1

    heldPosition(slot(45), shorter, RATE, TimeRange.from(slot(60)), playing = true) shouldBe slot(last)
  }

  private companion object {
    const val RATE = 30
    val LENGTH = 3.seconds
    val THIRD = 1.seconds / RATE / 3

    fun slot(index: Long): Duration = outputSlotTime(index, RATE)

    fun slot(index: Int): Duration = slot(index.toLong())
  }
}
