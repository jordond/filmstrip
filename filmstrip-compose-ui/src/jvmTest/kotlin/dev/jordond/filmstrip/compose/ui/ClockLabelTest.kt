package dev.jordond.filmstrip.compose.ui

import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ClockLabelTest {
  @Test
  fun `a label carries tenths only below a second`() {
    label(90.seconds + 400.milliseconds, 500.milliseconds) shouldBe "1:30.4"
    label(90.seconds + 400.milliseconds, 1.seconds) shouldBe "1:30"
  }

  @Test
  fun `the hours field appears only once there are hours`() {
    label(59.minutes + 59.seconds, 1.seconds) shouldBe "59:59"
    label(1.hours, 1.seconds) shouldBe "1:00:00"
    label(2.hours + 5.minutes + 7.seconds, 1.seconds) shouldBe "2:05:07"
  }

  @Test
  fun `minutes and seconds are padded but the leading field is not`() {
    label(Duration.ZERO, 1.seconds) shouldBe "0:00"
    label(9.seconds, 1.seconds) shouldBe "0:09"
    label(9.minutes + 5.seconds, 1.seconds) shouldBe "9:05"
  }

  @Test
  fun `a negative time reads as the start rather than as a negative clock`() {
    label((-5).seconds, 1.seconds) shouldBe "0:00"
  }

  @Test
  fun `two ticks a tick apart never read the same`() {
    // The finest step the ruler can choose. A label coarser than this would repeat itself, which is
    // what a tick ladder finer than the label would cause.
    val finest = TimelineScale(1.hours, 1_000_000f).tickInterval(minSpacingPx = 56f)

    label(finest, finest) shouldNotBeLabel label(finest * 2, finest)
  }

  private infix fun String.shouldNotBeLabel(other: String) {
    (this == other) shouldBe false
  }

  private fun label(
    time: Duration,
    interval: Duration,
  ): String = FilmstripTimelineDefaults.clockLabel(time, interval)
}
