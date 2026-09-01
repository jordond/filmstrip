package dev.jordond.filmstrip.compose.ui.geometry

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StripGridTest {
  @Test
  fun `tiles cover the content and sit at their leading edges`() {
    val grid = grid()

    // 30s at 100px per second is 3000px, which 50px tiles cover in exactly 60.
    grid.count shouldBe 60
    grid.positions.first() shouldBe Duration.ZERO
    grid.positions[1] shouldBe 500.milliseconds
    grid.positions[30] shouldBe 15.seconds
    grid.positions.last() shouldBe 29.seconds + 500.milliseconds
  }

  @Test
  fun `a partial tile at the end still counts`() {
    val grid = StripGrid(TimelineScale(31.seconds, PIXELS_PER_SECOND), TILE_WIDTH_PX)

    grid.count shouldBe 62
    grid.positions.last() shouldBe 30.seconds + 500.milliseconds
  }

  @Test
  fun `the visible range follows the scroll`() {
    val grid = grid()

    // Scrolled well into the strip and stopping well short of its end, so a range that is right
    // only at the edges fails here.
    grid.visibleRange(scrollPx = 725f, viewportWidthPx = 400f) shouldBe 14..22
    grid.visibleRange(scrollPx = 0f, viewportWidthPx = 400f) shouldBe 0..7
  }

  @Test
  fun `a range past either edge clamps into the strip`() {
    val grid = grid()

    grid.visibleRange(scrollPx = -200f, viewportWidthPx = 400f) shouldBe 0..3
    grid.visibleRange(scrollPx = 2900f, viewportWidthPx = 400f) shouldBe 58..59
  }

  @Test
  fun `a strip with nothing in it has no tiles and no visible range`() {
    val empty = StripGrid(TimelineScale(Duration.ZERO, PIXELS_PER_SECOND), TILE_WIDTH_PX)

    empty.count shouldBe 0
    empty.positions shouldBe emptyList()
    empty.visibleRange(scrollPx = 0f, viewportWidthPx = 400f) shouldBe IntRange.EMPTY
    empty.indexAt(1.seconds) shouldBe null

    StripGrid(TimelineScale(30.seconds, PIXELS_PER_SECOND), tileWidthPx = 0).count shouldBe 0
  }

  @Test
  fun `a time in the middle of the strip lands in the tile drawn over it`() {
    val grid = grid()

    grid.indexAt(15.seconds) shouldBe 30
    grid.indexAt(15.seconds + 250.milliseconds) shouldBe 30
    grid.indexAt(15.seconds + 500.milliseconds) shouldBe 31
    grid.indexAt(45.seconds) shouldBe 59
  }

  @Test
  fun `the last tile carries only the content that is left`() {
    // 31s at 100px per second is 3100px, which 50px tiles cover in 62 with 50px to spare, so every
    // tile is full. 30.7s is 3070px, where the last tile is a 20px remainder.
    val exact = StripGrid(TimelineScale(31.seconds, PIXELS_PER_SECOND), TILE_WIDTH_PX)
    exact.tileWidthPxAt(exact.count - 1) shouldBe TILE_WIDTH_PX

    val partial = StripGrid(TimelineScale(30.seconds + 700.milliseconds, PIXELS_PER_SECOND), TILE_WIDTH_PX)
    partial.count shouldBe 62
    partial.tileWidthPxAt(0) shouldBe TILE_WIDTH_PX
    partial.tileWidthPxAt(30) shouldBe TILE_WIDTH_PX
    partial.tileWidthPxAt(61) shouldBe 20
  }

  @Test
  fun `the tiles are exactly as wide as the content they cover`() {
    val grid = StripGrid(TimelineScale(30.seconds + 700.milliseconds, PIXELS_PER_SECOND), TILE_WIDTH_PX)
    val laidOut = (0 until grid.count).sumOf { grid.tileWidthPxAt(it) }

    laidOut shouldBe grid.scale.contentWidthPx.toInt()
  }

  private fun grid(): StripGrid = StripGrid(TimelineScale(30.seconds, PIXELS_PER_SECOND), TILE_WIDTH_PX)

  private companion object {
    const val PIXELS_PER_SECOND = 100f
    const val TILE_WIDTH_PX = 50
  }
}
