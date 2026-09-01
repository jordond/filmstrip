package dev.jordond.filmstrip.compose.ui.geometry

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimelineZoomTest {
  @Test
  fun `each step doubles the one below it`() {
    val bottom = TimelineZoom.of(TimelineZoom.Steps.first)

    bottom.pixelsPerSecond shouldBe 2f
    bottom.zoomedIn().pixelsPerSecond shouldBe 4f
    bottom.zoomedIn().zoomedIn().pixelsPerSecond shouldBe 8f
    TimelineZoom.of(8).pixelsPerSecond shouldBe 512f
  }

  @Test
  fun `the ladder stops at both ends`() {
    val bottom = TimelineZoom.of(TimelineZoom.Steps.first)
    val top = TimelineZoom.of(TimelineZoom.Steps.last)

    bottom.zoomedOut() shouldBe bottom
    top.zoomedIn() shouldBe top
    TimelineZoom.of(-40) shouldBe bottom
    TimelineZoom.of(400) shouldBe top
  }

  @Test
  fun `fitting picks the widest step the viewport still holds`() {
    // 30 seconds in 1000px wants 33.3 px per second, and the ladder's 32 is the step under it.
    val zoom = TimelineZoom.fitting(30.seconds, viewportWidthPx = 1000f)

    zoom.pixelsPerSecond shouldBe 32f
    zoom.scaleFor(30.seconds).contentWidthPx shouldBe 960f
    zoom.zoomedIn().scaleFor(30.seconds).contentWidthPx shouldBe 1920f
  }

  @Test
  fun `a source too long for any step fits at the bottom of the ladder`() {
    TimelineZoom.fitting(10.minutes, viewportWidthPx = 100f) shouldBe TimelineZoom.of(TimelineZoom.Steps.first)
    TimelineZoom.fitting(Duration.ZERO, viewportWidthPx = 1000f) shouldBe TimelineZoom.of(TimelineZoom.Steps.first)
    TimelineZoom.fitting(30.seconds, viewportWidthPx = 0f) shouldBe TimelineZoom.of(TimelineZoom.Steps.first)
  }

  @Test
  fun `zooming in keeps every position the step below it asked for`() {
    val duration = 30.seconds
    val out = StripGrid(TimelineZoom.of(5).scaleFor(duration), TILE_WIDTH_PX)
    val into = StripGrid(TimelineZoom.of(6).scaleFor(duration), TILE_WIDTH_PX)

    into.count shouldBe out.count * 2
    into.positions shouldContainAll out.positions

    // And each one lands where the nesting says it does, rather than merely being present.
    out.positions.forEachIndexed { index, position -> into.positions[index * 2] shouldBe position }
  }

  private companion object {
    const val TILE_WIDTH_PX = 46
  }
}
