package dev.jordond.filmstrip.player.internal

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where the Apple engine lets a shuttle frame land, between the frame showing and the playhead.
 */
class AvShuttleToleranceTest {
  @Test
  fun `a forward frame may land short of the playhead and never on the frame showing`() {
    val tolerance = shuttleToleranceOf(2_150.milliseconds, showing = 2.seconds, FRAME, BOUNDS)

    tolerance shouldBe SeekTolerance(before = 150.milliseconds - FRAME, after = Duration.ZERO)
  }

  @Test
  fun `a backward frame may land past the playhead and never on the frame showing`() {
    val tolerance = shuttleToleranceOf(1_850.milliseconds, showing = 2.seconds, FRAME, BOUNDS)

    tolerance shouldBe SeekTolerance(before = Duration.ZERO, after = 150.milliseconds - FRAME)
  }

  @Test
  fun `a playhead far from the frame showing lands within the shuttle tolerance of it`() {
    shuttleToleranceOf(3.seconds, showing = 1.seconds, FRAME, BOUNDS) shouldBe
      SeekTolerance(before = SHUTTLE_SEEK_TOLERANCE, after = Duration.ZERO)
    shuttleToleranceOf(1.seconds, showing = 3.seconds, FRAME, BOUNDS) shouldBe
      SeekTolerance(before = Duration.ZERO, after = SHUTTLE_SEEK_TOLERANCE)
  }

  @Test
  fun `a playhead one frame from the frame showing lands exactly`() {
    shuttleToleranceOf(2.seconds + FRAME, showing = 2.seconds, FRAME, BOUNDS) shouldBe SeekTolerance.Exact
    shuttleToleranceOf(2.seconds - FRAME, showing = 2.seconds, FRAME, BOUNDS) shouldBe SeekTolerance.Exact
  }

  @Test
  fun `a playhead on either bound lands exactly`() {
    shuttleToleranceOf(BOUNDS.endInclusive, showing = 2.seconds, FRAME, BOUNDS) shouldBe SeekTolerance.Exact
    shuttleToleranceOf(BOUNDS.start, showing = 2.seconds, FRAME, BOUNDS) shouldBe SeekTolerance.Exact
  }

  private companion object {
    val FRAME = 40.milliseconds
    val BOUNDS = Duration.ZERO..4_960.milliseconds
  }
}
