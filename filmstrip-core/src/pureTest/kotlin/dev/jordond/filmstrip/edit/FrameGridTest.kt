package dev.jordond.filmstrip.edit

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The one mapping from composition time onto the output frame grid, which every backend's video
 * lands on.
 *
 * The literals here are the rule itself. They match what ffmpeg's `tpad` and `fps` filters were
 * measured writing for the same starts and lengths, so they are not read off the function they check.
 */
class FrameGridTest {
  @Test
  fun `a time between two slots takes the nearer one and a time half way takes the later`() {
    outputSlotOf(19.milliseconds, frameRate = 25) shouldBe 0
    // Exactly half of a forty millisecond slot.
    outputSlotOf(20.milliseconds, frameRate = 25) shouldBe 1
    outputSlotOf(21.milliseconds, frameRate = 25) shouldBe 1
    outputSlotOf(10.milliseconds, frameRate = 30) shouldBe 0
    outputSlotOf(510.milliseconds, frameRate = 30) shouldBe 15
    outputSlotOf(520.milliseconds, frameRate = 30) shouldBe 16
    outputSlotOf(250.milliseconds, frameRate = 30) shouldBe 8
  }

  @Test
  fun `a whole second lands on its own slot at a rate that does not divide a microsecond count`() {
    outputSlotOf(1.seconds, frameRate = 30) shouldBe 30
    outputSlotOf(2.seconds, frameRate = 30) shouldBe 60
    outputSlotOf(10.seconds, frameRate = 30) shouldBe 300
  }

  // A floor, a ceiling and the nearest slot all agree on 1.000 and 1.040, so the times between them
  // are what tell the rule apart.
  @Test
  fun `a time inside a slot rounds to the nearer end of it`() {
    outputSlotOf(1_010.milliseconds, frameRate = 25) shouldBe 25
    outputSlotOf(1_020.milliseconds, frameRate = 25) shouldBe 26
    outputSlotOf(1_030.milliseconds, frameRate = 25) shouldBe 26
    outputSlotOf(1_039.milliseconds, frameRate = 25) shouldBe 26
    outputSlotOf(1_040.milliseconds, frameRate = 25) shouldBe 26
  }

  @Test
  fun `a time before the composition lands on its first slot`() {
    outputSlotOf(-1.seconds, frameRate = 30) shouldBe 0
    outputSlotOf(-Duration.INFINITE, frameRate = 30) shouldBe 0
  }

  @Test
  fun `an infinite time lands past every slot a real time can`() {
    (outputSlotOf(Duration.INFINITE, frameRate = 30) > outputSlotOf(24.hours, frameRate = 30)).shouldBeTrue()
    (outputSlotOf(Duration.INFINITE, frameRate = 240) > outputSlotOf(24.hours, frameRate = 240)).shouldBeTrue()
  }

  @Test
  fun `a rate that is not positive has no grid to land on`() {
    listOf(0, -1).forEach { rate ->
      assertFailsWith<IllegalArgumentException> { outputSlotOf(1.seconds, rate) }
      assertFailsWith<IllegalArgumentException> { outputSlotsOf(TimeRange.of(Duration.ZERO, 1.seconds), rate) }
      assertFailsWith<IllegalArgumentException> { outputSlotTime(1, rate) }
    }
  }

  @Test
  fun `a span covers the slots from where its start lands up to where its end lands`() {
    outputSlotsOf(TimeRange.of(1_010.milliseconds, 2_010.milliseconds), frameRate = 25) shouldBe (25L until 50L)
  }

  @Test
  fun `a span that reaches no point half way between two slots covers none`() {
    outputSlotsOf(TimeRange.of(Duration.ZERO, 10.milliseconds), frameRate = 25).isEmpty().shouldBeTrue()
    outputSlotsOf(TimeRange.of(1.seconds, 1_010.milliseconds), frameRate = 25).isEmpty().shouldBeTrue()
  }

  // Five milliseconds either side of the point half way between the first two slots, so a span a
  // quarter of a slot long still covers one. Its length alone does not say how many it covers.
  @Test
  fun `a short span that reaches a point half way between two slots covers the slot before it`() {
    outputSlotsOf(TimeRange.of(15.milliseconds, 25.milliseconds), frameRate = 25) shouldBe (0L until 1L)
  }

  @Test
  fun `a slot on a whole second reads that second exactly`() {
    outputSlotTime(30, frameRate = 30) shouldBe 1.seconds
    outputSlotTime(300, frameRate = 30) shouldBe 10.seconds
  }

  @Test
  fun `a slot between whole microseconds reads the one below it`() {
    outputSlotTime(1, frameRate = 30) shouldBe 33_333.microseconds
    outputSlotTime(1, frameRate = 24) shouldBe 41_666.microseconds
  }

  @Test
  fun `a time lands on a slot no more than half a slot away`() {
    listOf(
      20.milliseconds to 25,
      1_010.milliseconds to 25,
      1_039.milliseconds to 25,
      510.milliseconds to 30,
      1_021.milliseconds to 24,
    ).forEach { (time, rate) ->
      val halfSlot = (500_000 / rate).microseconds
      val landed = outputSlotTime(outputSlotOf(time, rate), rate)
      ((landed - time).absoluteValue <= halfSlot).shouldBeTrue()
    }
  }

  // A frame written at a slot's time has to be read back into that same slot, which the floor in
  // outputSlotTime and the nearest slot in outputSlotOf only agree on if neither drifts.
  @Test
  fun `every slot's own time lands back on that slot`() {
    listOf(24, 25, 30, 48, 60, 90, 144).forEach { rate ->
      for (slot in 0L until rate * 3L) {
        outputSlotOf(outputSlotTime(slot, rate), rate) shouldBe slot
      }
    }
  }
}
