package dev.jordond.filmstrip.transform.internal

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Which trims hold no frame of a source, read against the source's own frame grid.
 */
class TrimFramesTest {
  private val frame = 1.seconds / RATE

  @Test
  fun `a trim between two frames holds none`() {
    trimHoldsNoFrame(frame * 0.3, frame * 0.9, RATE) shouldBe true
    trimHoldsNoFrame(frame * 20.3, frame * 20.9, RATE) shouldBe true
  }

  @Test
  fun `a trim reaching the next frame holds it`() {
    trimHoldsNoFrame(frame * 0.3, frame * 1.1, RATE) shouldBe false
  }

  @Test
  fun `a trim opening on a frame holds that frame`() {
    trimHoldsNoFrame(Duration.ZERO, 1.milliseconds, RATE) shouldBe false
    trimHoldsNoFrame(frame, frame + 1.milliseconds, RATE) shouldBe false
  }

  // Closes on a frame's own time, which belongs to the next clip.
  @Test
  fun `a trim ending on a frame does not hold it`() {
    trimHoldsNoFrame(frame * 0.3, frame, RATE) shouldBe true
  }

  private companion object {
    const val RATE = 30.0
  }
}
