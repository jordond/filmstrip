package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the desktop pump wraps a loop range, stops a playback range and answers the seek it was opened for, taken apart
 * from the pump that asks.
 */
class FfmpegRangeEndTest {
  private val plan = fixturePlan()
  private val rate = plan.frameRate
  private val ends = Recorder()

  @Test
  fun `a loop range wraps on the first slot past its last and not before`() {
    val range = TimeRange.of(300.milliseconds, 900.milliseconds)
    val slots = outputSlotsOf(range, rate)
    ends.subject.setLoopRange(range)

    ends.subject.reachedEnd(slotTime(slots.last), plan) shouldBe false
    ends.subject.reachedEnd(slotTime((slots.first + slots.last) / 2), plan) shouldBe false
    ends.wraps.shouldBeEmpty()

    ends.subject.reachedEnd(slotTime(slots.last + 1), plan) shouldBe true
    ends.wraps shouldBe listOf(slotTime(slots.first))
    ends.laps shouldBe listOf(range)
  }

  @Test
  fun `a frame past a loop range stops the pump without wrapping while a seek is in flight`() {
    val range = TimeRange.of(300.milliseconds, 900.milliseconds)
    ends.subject.setLoopRange(range)
    ends.subject.seekIssued(wrap = false) { }

    ends.subject.reachedEnd(slotTime(outputSlotsOf(range, rate).last + 1), plan) shouldBe true

    ends.wraps.shouldBeEmpty()
    ends.laps.shouldBeEmpty()
  }

  @Test
  fun `a frame past a loop range stops a paused pump without wrapping`() {
    val range = TimeRange.of(300.milliseconds, 900.milliseconds)
    ends.subject.setLoopRange(range)
    ends.wanted.value = false

    ends.subject.reachedEnd(slotTime(outputSlotsOf(range, rate).last + 1), plan) shouldBe true

    ends.wraps.shouldBeEmpty()
  }

  @Test
  fun `a playback range finishes on the first slot past its last`() {
    val range = TimeRange.of(Duration.ZERO, 610.milliseconds)
    val last = lastSlotOf(range, rate) ?: error("$range has an end")
    ends.subject.playbackRange = range

    ends.subject.reachedEnd(slotTime(last / 2), plan) shouldBe false
    ends.subject.reachedEnd(slotTime(last), plan) shouldBe false
    ends.finishes.shouldBeEmpty()

    ends.subject.reachedEnd(slotTime(last + 1), plan) shouldBe true
    ends.finishes shouldBe listOf(true)
  }

  @Test
  fun `a playback range with no end never stops the pump`() {
    ends.subject.playbackRange = TimeRange.from(300.milliseconds)

    ends.subject.reachedEnd(plan.resolved.duration, plan) shouldBe false
    ends.finishes.shouldBeEmpty()
  }

  @Test
  fun `holding a new landing answers the one before it at the playhead`() {
    val first = mutableListOf<Duration>()
    val second = mutableListOf<Duration>()

    ends.subject.hold { first += it }
    ends.position = 400.milliseconds
    ends.subject.hold { second += it }

    first shouldBe listOf(400.milliseconds)
    second.shouldBeEmpty()

    ends.position = 500.milliseconds
    ends.subject.settleLanding()
    ends.subject.settleLanding()
    second shouldBe listOf(500.milliseconds)
  }

  @Test
  fun `a dropped landing is never answered`() {
    val answers = mutableListOf<Duration>()
    ends.subject.hold { answers += it }

    ends.subject.dropLanding()
    ends.subject.settleLanding()

    answers.shouldBeEmpty()
  }

  @Test
  fun `a wrap landing after its loop range moved is redirected to the new range's first slot`() {
    val moved = TimeRange.of(600.milliseconds, 1_200.milliseconds)
    val answers = mutableListOf<Duration>()
    val seek: (Duration) -> Unit = { answers += it }
    ends.subject.setLoopRange(TimeRange.of(300.milliseconds, 900.milliseconds))
    ends.subject.seekIssued(wrap = true, seek)
    ends.subject.hold(seek)

    ends.subject.setLoopRange(moved)
    val slot = ends.subject.redirectOf(plan)

    slot shouldBe outputSlotOf(moved.start, rate)
    ends.subject.redirect(slot ?: error("no redirect"), plan)
    ends.position shouldBe slotTime(slot)
    ends.pumps.map { it.first } shouldBe listOf(slotTime(slot))
    ends.pumps.single().second shouldBe seek
    answers.shouldBeEmpty()
  }

  @Test
  fun `setting the loop range already standing never redirects a wrap`() {
    val range = TimeRange.of(300.milliseconds, 900.milliseconds)
    val seek: (Duration) -> Unit = { }
    ends.subject.setLoopRange(range)
    ends.subject.seekIssued(wrap = true, seek)
    ends.subject.hold(seek)

    ends.subject.setLoopRange(range)

    ends.subject.redirectOf(plan).shouldBeNull()
  }

  @Test
  fun `only the latest seek's landing is put to the guard`() {
    val stale: (Duration) -> Unit = { }
    ends.subject.setLoopRange(TimeRange.of(300.milliseconds, 900.milliseconds))
    ends.subject.seekIssued(wrap = true) { }
    ends.subject.hold(stale)

    ends.subject.setLoopRange(TimeRange.of(600.milliseconds, 1_200.milliseconds))

    ends.subject.redirectOf(plan).shouldBeNull()
  }

  @Test
  fun `a pump that ran out on the composition's last slot wraps a loop range with no end`() {
    val range = TimeRange.from(300.milliseconds)
    val wrap = loopWrapOf(range, plan.resolved.duration, rate) ?: error("$range has nothing to loop")
    ends.subject.setLoopRange(range)
    ends.presented = PreviewFrame(slotTime(wrap.lastSlot), ByteArray(0))

    ends.subject.ranOut(landed = true, plan)

    ends.wraps shouldBe listOf(slotTime(wrap.firstSlot))
    ends.laps shouldBe listOf(range)
    ends.finishes.shouldBeEmpty()
  }

  @Test
  fun `a pump that ran out short of the composition's last slot finishes rather than wrapping`() {
    val range = TimeRange.from(300.milliseconds)
    val wrap = loopWrapOf(range, plan.resolved.duration, rate) ?: error("$range has nothing to loop")
    ends.subject.setLoopRange(range)
    ends.presented = PreviewFrame(slotTime((wrap.firstSlot + wrap.lastSlot) / 2), ByteArray(0))

    ends.subject.ranOut(landed = true, plan)

    ends.wraps.shouldBeEmpty()
    ends.finishes shouldBe listOf(true)
  }

  @Test
  fun `a pump that ran out under a seek in flight is left to that seek`() {
    val range = TimeRange.from(300.milliseconds)
    val wrap = loopWrapOf(range, plan.resolved.duration, rate) ?: error("$range has nothing to loop")
    ends.subject.setLoopRange(range)
    ends.subject.seekIssued(wrap = false) { }
    ends.presented = PreviewFrame(slotTime(wrap.lastSlot), ByteArray(0))

    ends.subject.ranOut(landed = true, plan)

    ends.wraps.shouldBeEmpty()
    ends.finishes.shouldBeEmpty()
  }

  @Test
  fun `a pump that ran out with no loop range finishes with whether it landed`() {
    ends.subject.ranOut(landed = false, plan)

    ends.finishes shouldBe listOf(false)
  }

  @Test
  fun `a playback range's last slot is the last one it carries`() {
    lastSlotOf(TimeRange.from(300.milliseconds), rate).shouldBeNull()

    val whole = TimeRange.of(Duration.ZERO, 600.milliseconds)
    lastSlotOf(whole, rate) shouldBe outputSlotsOf(whole, rate).last

    val midFrame = TimeRange.of(Duration.ZERO, 610.milliseconds)
    lastSlotOf(midFrame, rate) shouldBe outputSlotsOf(midFrame, rate).last
  }

  @Test
  fun `a playback range narrower than a slot ends on its own first slot`() {
    val narrow = TimeRange.of(605.milliseconds, 610.milliseconds)
    outputSlotsOf(narrow, rate).isEmpty() shouldBe true

    lastSlotOf(narrow, rate) shouldBe outputSlotOf(narrow.start, rate)
  }

  private fun slotTime(slot: Long): Duration = outputSlotTime(slot, rate)

  /**
   * An [FfmpegRangeEnd] whose engine side is recorded rather than acted on.
   */
  private class Recorder {
    var presented: PreviewFrame? = null
    var position: Duration = Duration.ZERO
    val wanted = MutableStateFlow(true)
    val pumps = mutableListOf<Pair<Duration, ((Duration) -> Unit)?>>()
    val wraps = mutableListOf<Duration>()
    val laps = mutableListOf<TimeRange>()
    val finishes = mutableListOf<Boolean>()

    val subject =
      FfmpegRangeEnd(
        presented = { presented },
        position = { position },
        moveTo = { position = it },
        wanted = wanted,
        startPump = { from, onLanded -> pumps += from to onLanded },
        seekToWrap = { wraps += it },
        looped = { laps += it },
        finish = { finishes += it },
      )
  }
}
