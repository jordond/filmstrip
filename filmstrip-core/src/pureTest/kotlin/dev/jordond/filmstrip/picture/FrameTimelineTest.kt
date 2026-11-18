package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.FramePlays
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FrameTimelineTest {
  private val delays = listOf(100.milliseconds, 30.milliseconds, 250.milliseconds, 10.milliseconds, 70.milliseconds)

  @Test
  fun uniformFramesShowInOrderOnTheirOwnSlots() {
    val timeline = FrameTimeline.uniform(12, 1.seconds / 30, FramePlays.Forever)

    (0L until 12L).map { timeline.frameOnSlot(Duration.ZERO, it, 30) } shouldBe (0..11).toList()
  }

  @Test
  fun uniformFramesHoldWhenSlotTimesAreTruncatedToWholeMicroseconds() {
    val timeline = FrameTimeline.uniform(12, 1.seconds / 30, FramePlays.Forever)

    val shown =
      (0L until 12L).map {
        val truncated = outputSlotTime(it, 30).inWholeMicroseconds.microseconds
        timeline.frameAt(Duration.ZERO, truncated, 30)
      }

    shown shouldBe (0..11).toList()
  }

  @Test
  fun aFrameTimeThatDoesNotDivideASecondDoesNotDriftAtSixtyFps() {
    val timeline = FrameTimeline.uniform(12, 1.seconds / 60, FramePlays.Forever)

    for (slot in 499_900L..500_100L) {
      timeline.frameOnSlot(Duration.ZERO, slot, 60) shouldBe (slot % 12).toInt()
    }
    timeline.frameOnSlot(Duration.ZERO, 500_000L, 60) shouldBe (500_000 % 12)
  }

  @Test
  fun listedEqualDurationsBuildTheSameTimelineAsUniform() {
    val listed = FrameTimeline(List(12) { 1.seconds / 60 }, FramePlays.Forever)
    val uniform = FrameTimeline.uniform(12, 1.seconds / 60, FramePlays.Forever)

    for (slot in 0L..2_000L) {
      listed.frameOnSlot(Duration.ZERO, slot, 60) shouldBe uniform.frameOnSlot(Duration.ZERO, slot, 60)
    }
    listed.cycle shouldBe uniform.cycle
  }

  @Test
  fun variableDelaysMatchABruteForceWalkOnEverySlotOfThreeCycles() {
    for (rate in listOf(24, 30, 60)) {
      val timeline = FrameTimeline(delays, FramePlays.Forever)
      val cycle = delays.fold(Duration.ZERO) { sum, delay -> sum + delay }
      val lastSlot = outputSlotOf(cycle * 3, rate)

      for (slot in 0L..lastSlot) {
        timeline.frameOnSlot(Duration.ZERO, slot, rate) shouldBe walk(Duration.ZERO, slot, rate, 20)
      }
    }
  }

  @Test
  fun variableDelaysMatchTheWalkWhenTheWindowOpensOffTheGrid() {
    val start = 1234.milliseconds
    val timeline = FrameTimeline(delays, FramePlays.Forever)

    for (slot in 0L..200L) {
      timeline.frameOnSlot(start, slot, 30) shouldBe walk(start, slot, 30, 20)
    }
  }

  @Test
  fun aSlotBeforeTheDrawnStartShowsFrameZero() {
    val timeline = FrameTimeline(delays, FramePlays.Forever)

    timeline.frameOnSlot(1234.milliseconds, 0, 30) shouldBe 0
    timeline.frameOnSlot(1234.milliseconds, 36, 30) shouldBe 0
  }

  @Test
  fun aTimestampIsPutOnItsSlotBeforeTheFrameIsRead() {
    val timeline = FrameTimeline(delays, FramePlays.Forever)

    for (slot in 0L..200L) {
      val late = outputSlotTime(slot, 30) + 16.milliseconds
      timeline.frameAt(1234.milliseconds, late, 30) shouldBe
        timeline.frameOnSlot(1234.milliseconds, outputSlotOf(late, 30), 30)
    }
  }

  @Test
  fun framesLandingOnOneSlotShowTheLater() {
    val timeline =
      FrameTimeline(listOf(100.milliseconds, 5.milliseconds, 100.milliseconds, 100.milliseconds), FramePlays.Forever)

    timeline.frameOnSlot(Duration.ZERO, 2, 30) shouldBe 0
    timeline.frameOnSlot(Duration.ZERO, 3, 30) shouldBe 2
    timeline.frameOnSlot(Duration.ZERO, 6, 30) shouldBe 3
  }

  @Test
  fun timesHoldsTheLastFrameAfterTheFinalCycle() {
    val timeline = FrameTimeline.uniform(4, 1.seconds / 30, FramePlays.Times(3))

    timeline.frameOnSlot(Duration.ZERO, 11, 30) shouldBe 3
    timeline.frameOnSlot(Duration.ZERO, 12, 30) shouldBe 3
    timeline.frameOnSlot(Duration.ZERO, 5_000_000, 30) shouldBe 3
    timeline.frameOnSlot(Duration.ZERO, 6, 30) shouldBe 2
  }

  @Test
  fun foreverWrapsToTheFirstFrame() {
    val timeline = FrameTimeline.uniform(4, 1.seconds / 30, FramePlays.Forever)

    timeline.frameOnSlot(Duration.ZERO, 4, 30) shouldBe 0
    timeline.frameOnSlot(Duration.ZERO, 4_000_001, 30) shouldBe (4_000_001 % 4)
  }

  @Test
  fun cycleAndPlayTimeAreReadOffTheDurations() {
    FrameTimeline(delays, FramePlays.Forever).cycle shouldBe 460.milliseconds
    FrameTimeline(delays, FramePlays.Forever).playTime shouldBe null
    FrameTimeline(delays, FramePlays.Times(3)).playTime shouldBe 1380.milliseconds
    FrameTimeline(delays, FramePlays.Once).frameCount shouldBe 5
  }

  @Test
  fun badInputIsRefused() {
    assertFailsWith<IllegalArgumentException> { FrameTimeline(emptyList(), FramePlays.Forever) }
    assertFailsWith<IllegalArgumentException> { FrameTimeline(listOf(Duration.ZERO), FramePlays.Forever) }
    assertFailsWith<IllegalArgumentException> { FrameTimeline(listOf((-1).milliseconds), FramePlays.Forever) }
    assertFailsWith<IllegalArgumentException> { FrameTimeline(listOf(Duration.INFINITE), FramePlays.Forever) }
    assertFailsWith<IllegalArgumentException> { FrameTimeline.uniform(0, 1.seconds, FramePlays.Forever) }
    assertFailsWith<IllegalArgumentException> { FramePlays.Times(0) }
  }

  @Test
  fun onceIsOnePlay() {
    FramePlays.Once shouldBe FramePlays.Times(1)
  }

  /** Lands every frame start with [outputSlotOf] and keeps the last one at or before [slot]. */
  private fun walk(
    drawnStart: Duration,
    slot: Long,
    rate: Int,
    cycles: Int,
  ): Int {
    var shown = 0
    var start = drawnStart
    var index = 0
    repeat(cycles) {
      for (delay in delays) {
        if (outputSlotOf(start, rate) <= slot) shown = index
        start += delay
        index++
      }
    }
    return shown % delays.size
  }
}
