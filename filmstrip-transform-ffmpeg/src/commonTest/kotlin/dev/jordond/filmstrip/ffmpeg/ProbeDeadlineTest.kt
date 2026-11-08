package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.process.PROBE_DEADLINE_BASE
import dev.jordond.filmstrip.ffmpeg.internal.process.PROBE_DEADLINE_WINDOW_FRACTION
import dev.jordond.filmstrip.ffmpeg.internal.process.probeDeadline
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProbeDeadlineTest {
  @Test
  fun `a read of no window gets the base`() {
    probeDeadline() shouldBe PROBE_DEADLINE_BASE
  }

  // A multiplicative reading of the base would agree at zero and drift apart here.
  @Test
  fun `a read of a window gets its share of the window on top of the base`() {
    probeDeadline(5.seconds) shouldBe PROBE_DEADLINE_BASE + 5.seconds * PROBE_DEADLINE_WINDOW_FRACTION
    probeDeadline(1_250.milliseconds) shouldBe PROBE_DEADLINE_BASE + 1_250.milliseconds * PROBE_DEADLINE_WINDOW_FRACTION
  }

  @Test
  fun `a long window keeps growing the deadline`() {
    probeDeadline(2.hours) shouldBe PROBE_DEADLINE_BASE + 2.hours * PROBE_DEADLINE_WINDOW_FRACTION
  }

  @Test
  fun `a window that ends before it opens gets the base`() {
    probeDeadline((-2).seconds) shouldBe PROBE_DEADLINE_BASE
  }
}
