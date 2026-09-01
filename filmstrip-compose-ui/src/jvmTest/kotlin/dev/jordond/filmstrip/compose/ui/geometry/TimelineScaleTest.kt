package dev.jordond.filmstrip.compose.ui.geometry

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimelineScaleTest {
  @Test
  fun `a time in the middle of the range survives a round trip through pixels`() {
    val scale = TimelineScale(30.seconds, PIXELS_PER_SECOND)

    // Deliberately not an endpoint. Zero and the duration agree under any mapping that gets the
    // ends right, including one that is wrong everywhere between them.
    val middle = 11.seconds + 250.milliseconds
    scale.xOf(middle) shouldBe 1125f
    scale.timeAt(1125f) shouldBe middle
  }

  @Test
  fun `content is as wide as the duration times the zoom`() {
    TimelineScale(30.seconds, PIXELS_PER_SECOND).contentWidthPx shouldBe 3000f
    TimelineScale(Duration.ZERO, PIXELS_PER_SECOND).contentWidthPx shouldBe 0f
  }

  @Test
  fun `a coordinate outside the timeline clamps into it`() {
    val scale = TimelineScale(30.seconds, PIXELS_PER_SECOND)

    scale.timeAt(-500f) shouldBe Duration.ZERO
    scale.timeAt(9_000f) shouldBe 30.seconds
    scale.xOf((-2).seconds) shouldBe 0f
    scale.xOf(45.seconds) shouldBe 3000f
  }

  @Test
  fun `a span is measured unclamped, unlike a coordinate`() {
    val scale = TimelineScale(30.seconds, PIXELS_PER_SECOND)

    scale.widthOf(45.seconds) shouldBe 4500f
    scale.xOf(45.seconds) shouldBe 3000f
  }

  @Test
  fun `a zoom with no pixels in it maps everything to zero`() {
    val scale = TimelineScale(30.seconds, 0f)

    scale.contentWidthPx shouldBe 0f
    scale.timeAt(500f) shouldBe Duration.ZERO
    scale.tickInterval(minSpacingPx = 64f) shouldBe 1.hours
  }

  @Test
  fun `the tick unit follows the zoom`() {
    fun intervalAt(pixelsPerSecond: Float): Duration =
      TimelineScale(1.hours, pixelsPerSecond).tickInterval(minSpacingPx = 64f)

    // Between the thresholds rather than on them, so a ladder that is off by one step still fails.
    intervalAt(0.35f) shouldBe 5.minutes
    intervalAt(3f) shouldBe 30.seconds
    intervalAt(9f) shouldBe 10.seconds
    intervalAt(20f) shouldBe 5.seconds
    intervalAt(100f) shouldBe 1.seconds
    intervalAt(200f) shouldBe 500.milliseconds
    intervalAt(900f) shouldBe 100.milliseconds
  }

  @Test
  fun `the chosen tick is the first one wide enough, not merely a wide one`() {
    val scale = TimelineScale(1.hours, 20f)
    val interval = scale.tickInterval(minSpacingPx = 64f)

    (scale.widthOf(interval) >= 64f) shouldBe true
    (scale.widthOf(interval / 5) < 64f) shouldBe true
  }

  private companion object {
    const val PIXELS_PER_SECOND = 100f
  }
}
