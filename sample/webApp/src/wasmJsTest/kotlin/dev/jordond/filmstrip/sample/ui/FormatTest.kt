package dev.jordond.filmstrip.sample.ui

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FormatTest {
  @Test
  fun `zero is all zeros`() {
    timecode(0.0, 30f) shouldBe "00:00:00:00"
  }

  @Test
  fun `mid second counts frames at the source rate`() {
    timecode(4.2, 30f) shouldBe "00:00:04:06"
    timecode(4.5, 24f) shouldBe "00:00:04:12"
    timecode(0.5, 60f) shouldBe "00:00:00:30"
  }

  @Test
  fun `a position that came through a Float lands on its frame`() {
    timecode(0.7f.toDouble(), 30f) shouldBe "00:00:00:21"
  }

  @Test
  fun `29_97 stays on the second of the real clock and never prints frame 30`() {
    timecode(1.5, 29.97f) shouldBe "00:00:01:14"
    timecode(60.0, 29.97f) shouldBe "00:01:00:00"
    timecode(59.9999, 29.97f) shouldBe "00:00:59:29"
  }

  @Test
  fun `rolls over past a minute and an hour`() {
    timecode(61.5, 30f) shouldBe "00:01:01:15"
    timecode(3_661.0, 30f) shouldBe "01:01:01:00"
  }

  @Test
  fun `an unknown rate reads at 30`() {
    timecode(1.5, null) shouldBe "00:00:01:15"
    timecode(1.5, 0f) shouldBe "00:00:01:15"
  }
}
