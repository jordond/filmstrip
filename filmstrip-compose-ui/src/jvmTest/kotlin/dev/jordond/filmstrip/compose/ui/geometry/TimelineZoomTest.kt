package dev.jordond.filmstrip.compose.ui.geometry

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.pow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
  fun `of keeps the ladder's numbers at every step`() {
    TimelineZoom.Steps.forEach { step ->
      TimelineZoom.of(step).pixelsPerSecond shouldBe 2f.pow(step + 1)
    }
  }

  @Test
  fun `fitting fills the viewport without going over it`() {
    FIT_DURATIONS.forEach { duration ->
      FIT_WIDTHS.forEach { viewportWidthPx ->
        val contentWidthPx = TimelineZoom.fitting(duration, viewportWidthPx).scaleFor(duration).contentWidthPx

        withClue("$duration on $viewportWidthPx px") {
          contentWidthPx shouldBeLessThanOrEqual viewportWidthPx
          (viewportWidthPx - contentWidthPx) shouldBeLessThanOrEqual 1f
        }
      }
    }
  }

  @Test
  fun `fitting a viewport that sits on a ladder step lands on that step`() {
    TimelineZoom.fitting(10.seconds, viewportWidthPx = 1280f) shouldBe TimelineZoom.of(6)
  }

  @Test
  fun `a fit between two steps is not the step it sits on`() {
    val fit = TimelineZoom.fitting(30.seconds, viewportWidthPx = 1000f)

    fit.step shouldBe 4
    fit shouldNotBe TimelineZoom.of(4)
  }

  @Test
  fun `a fit never overflows by rounding a viewport measured through dp`() {
    val tileWidthPx = TILE_WIDTH_PX
    val duration = 7300.milliseconds

    listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f).forEach { density ->
      (8..80).forEach { tiles ->
        val laidOutPx = tiles * tileWidthPx
        val viewportWidthPx = laidOutPx / density * density

        val grid = StripGrid(TimelineZoom.fitting(duration, viewportWidthPx).scaleFor(duration), tileWidthPx)

        withClue("$laidOutPx px at density $density") {
          grid.count shouldBe tiles
          grid.tileWidthPxAt(grid.count - 1) shouldBe tileWidthPx
        }
      }
    }
  }

  @Test
  fun `a fractional viewport fits inside its whole pixels`() {
    val contentWidthPx = TimelineZoom.fitting(30.seconds, viewportWidthPx = 1000.5f).scaleFor(30.seconds).contentWidthPx

    contentWidthPx shouldBeLessThanOrEqual 1000f
    contentWidthPx shouldBeGreaterThan 999f
  }

  @Test
  fun `every step from a fit still doubles`() {
    val fit = TimelineZoom.fitting(30.seconds, viewportWidthPx = 1000f)

    fit.zoomedIn().zoomedIn().pixelsPerSecond shouldBe fit.pixelsPerSecond * 4f
    fit.zoomedOut().pixelsPerSecond shouldBe fit.pixelsPerSecond * 0.5f
    fit.zoomedIn().step shouldBe fit.step + 1
    fit.zoomedOut().step shouldBe fit.step - 1
  }

  @Test
  fun `the ladder from a fit stops at both ends`() {
    val fit = TimelineZoom.fitting(30.seconds, viewportWidthPx = 1000f)
    val bottom = (0..TimelineZoom.Steps.last).fold(fit) { zoom, _ -> zoom.zoomedOut() }
    val top = (0..TimelineZoom.Steps.last).fold(fit) { zoom, _ -> zoom.zoomedIn() }

    bottom.step shouldBe TimelineZoom.Steps.first
    top.step shouldBe TimelineZoom.Steps.last
    bottom.zoomedOut() shouldBe bottom
    top.zoomedIn() shouldBe top
  }

  @Test
  fun `a source too long for the bottom step scrolls there`() {
    TimelineZoom.fitting(10.minutes, viewportWidthPx = 100f) shouldBe TimelineZoom.of(TimelineZoom.Steps.first)
  }

  @Test
  fun `nothing to fit stays at the bottom of the ladder`() {
    val bottom = TimelineZoom.of(TimelineZoom.Steps.first)

    TimelineZoom.fitting(Duration.ZERO, viewportWidthPx = 1000f) shouldBe bottom
    TimelineZoom.fitting(30.seconds, viewportWidthPx = 0f) shouldBe bottom
    TimelineZoom.fitting(30.seconds, viewportWidthPx = Float.POSITIVE_INFINITY) shouldBe bottom
    TimelineZoom.fitting(30.seconds, viewportWidthPx = Float.NaN) shouldBe bottom
    TimelineZoom.fitting(Duration.INFINITE, viewportWidthPx = 1000f) shouldBe bottom
  }

  @Test
  fun `a source too short for the top step still fills`() {
    val fit = TimelineZoom.fitting(100.milliseconds, viewportWidthPx = 1000f)
    val contentWidthPx = fit.scaleFor(100.milliseconds).contentWidthPx

    fit.step shouldBe TimelineZoom.Steps.last
    contentWidthPx shouldBeLessThanOrEqual 1000f
    contentWidthPx shouldBeGreaterThan 999f
    fit.zoomedOut().pixelsPerSecond shouldBe fit.pixelsPerSecond * 0.5f
  }

  @Test
  fun `every step of a fit's ladder keeps every position the step below it asked for`() {
    FIT_DURATIONS.forEach { duration ->
      FIT_WIDTHS.forEach { viewportWidthPx ->
        val fit = TimelineZoom.fitting(duration, viewportWidthPx)
        val bottom = generateSequence(fit) { it.zoomedOut() }.first { it.step == TimelineZoom.Steps.first }

        generateSequence(bottom) { it.zoomedIn() }
          .takeWhile { it.step < TimelineZoom.Steps.last }
          .forEach { out ->
            val outer = StripGrid(out.scaleFor(duration), TILE_WIDTH_PX)
            val inner = StripGrid(out.zoomedIn().scaleFor(duration), TILE_WIDTH_PX)

            withClue("$duration on $viewportWidthPx px from step ${out.step}") {
              outer.positions.forEachIndexed { index, position -> inner.positions[index * 2] shouldBe position }
            }
          }
      }
    }
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
    val FIT_DURATIONS: List<Duration> =
      listOf(100.milliseconds, 1.seconds, 7300.milliseconds, 10.seconds, 30.seconds, 61700.milliseconds, 90.seconds)
    val FIT_WIDTHS: List<Float> = listOf(390f, 411f, 1000f, 1272f, 1280f, 1288f, 1920f, 2560f, 3839f)
  }
}
