package dev.jordond.filmstrip.transform.internal.encode

import dev.jordond.filmstrip.export.ExportSpec
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How [keyFrameIntervalOf] counts a spec's key frame interval in frames of the output rate.
 */
class KeyFrameIntervalTest {
  @Test
  fun `no interval is one second of frames`() {
    DEFAULT_KEY_FRAME_INTERVAL shouldBe 1.seconds
    for (rate in listOf(24, 30, 60)) keyFrameIntervalOf(ExportSpec(), rate) shouldBe rate
  }

  @Test
  fun `one second is the frame rate`() {
    framesOf(1.seconds, 30) shouldBe 30
    framesOf(1.seconds, 25) shouldBe 25
  }

  @Test
  fun `half a second is half the frame rate`() {
    framesOf(500.milliseconds, 30) shouldBe 15
    framesOf(500.milliseconds, 60) shouldBe 30
  }

  @Test
  fun `an interval between two frames rounds to the nearer one and a half rounds up`() {
    framesOf(500.milliseconds, 25) shouldBe 13
    framesOf(400.milliseconds, 24) shouldBe 10
    framesOf(700.milliseconds, 24) shouldBe 17
    framesOf(2.5.seconds, 30) shouldBe 75
  }

  @Test
  fun `an interval shorter than a frame is every frame`() {
    framesOf(1.milliseconds, 30) shouldBe 1
    framesOf(16.milliseconds, 30) shouldBe 1
  }

  @Test
  fun `an interval longer than any count of frames holds at the largest`() {
    framesOf(Duration.INFINITE, 30) shouldBe Int.MAX_VALUE
  }

  private fun framesOf(
    interval: Duration,
    frameRate: Int,
  ): Int = keyFrameIntervalOf(ExportSpec(keyFrameInterval = interval), frameRate)
}
