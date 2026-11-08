package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.planesAtExactTicks
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * A Film drawn from live planes reads no differing sample against its `geq` graph, on the whole raster of a frame.
 */
class FfmpegFilmPlanesMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a clip film at 1080p`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(1920, 1080, Film())))
      assertFormsAgree(plan, frames = 12)
    }

  @Test
  fun `a clip film at 4k reads its whole cell raster`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(3840, 2160, Film())))
      assertFormsAgree(plan, frames = 6)
    }

  @Test
  fun `a frame with no whole cell draws each pixel`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(1280, 720, Film())))
      assertFormsAgree(plan, frames = 12)
    }

  @Test
  fun `grain alone has no gbrap ahead of it`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(640, 360, Film(grain = 0.5f, halation = 0f))))
      assertFormsAgree(plan, frames = 12)
    }

  @Test
  fun `a composition film across two clips`() =
    runBlocking<Unit> {
      val plain = clip(640, 360)
      val plan = draw.composition(listOf(plain, plain), listOf(Film()))
      assertFormsAgree(plan, frames = 70)
    }

  @Test
  fun `a clip that opens at two seconds reads its own ticks`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(640, 360), clip(640, 360, Film())))
      assertFormsAgree(plan, frames = 72)
    }

  @Test
  fun `a preview window reads the ticks it opened on`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(640, 360, Film())))
      assertFormsAgree(plan, frames = 10, at = 1200.milliseconds)
    }

  @Test
  fun `a 23_976 clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("24000/1001") }

  @Test
  fun `a 29_97 clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("30000/1001") }

  @Test
  fun `a 59_94 clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("60000/1001") }

  @Test
  fun `a 30 fps clip with a frame 3_667 us under a tick opening`() =
    runBlocking<Unit> { assertAgreesPlaced("30", 24_008_330.microseconds, frames = 724) }

  @Test
  fun `grain alone keeps the alpha that marks an unpainted bar`() =
    runBlocking<Unit> {
      val film = Film(grain = 0.5f, halation = 0f)
      val contain = Crop(AspectRatio.Square, fit = Fit.Contain)
      val plan =
        draw.composition(
          listOf(draw.clipOf(640, 360, ::ramp, listOf(contain, film))),
          fill = Fill.Solid(0xFF2F4F6F.toInt()),
        )
      assertFormsAgree(plan, frames = 8)
    }

  @Test
  fun `a 24 fps clip reads a new tick on every frame on both forms`() =
    runBlocking<Unit> {
      assertReadsExactTicks("24", "N")
      val clip = draw.clipOf(160, 90, ::ramp, listOf(LOOK), rate = "24", container = "mov")
      val drawn = draw.formPlanes(draw.composition(listOf(clip)), TICK_FRAMES, live = true)
      val size = drawn.size / TICK_FRAMES
      val frames = List(TICK_FRAMES) { drawn.copyOfRange(it * size, (it + 1) * size).toList() }
      frames.zipWithNext().count { (one, next) -> one == next } shouldBe 0
    }

  @Test
  fun `a 48 fps clip holds each tick for two frames`() = runBlocking<Unit> { assertReadsExactTicks("48", "floor(N/2)") }

  @Test
  fun `a 30 fps clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("30", "floor(N*24/30)") }

  @Test
  fun `a 60 fps clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("60", "floor(N*24/60)") }

  @Test
  fun `a 23_976 clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("24000/1001", "floor(N*1001/1000)") }

  private suspend fun assertFormsAgree(
    plan: EditComposition,
    frames: Int,
    at: Duration = Duration.ZERO,
  ) {
    val drawn = draw.formPlanes(plan, frames, live = false, at = at)
    val planes = draw.formPlanes(plan, frames, live = true, at = at)
    val differing = drawn.indices.count { drawn[it] != planes[it] }
    withClue("$differing of ${drawn.size} samples differ") { differing shouldBe 0 }
    val frameBytes = drawn.size / frames
    drawn.copyOfRange(0, frameBytes).contentEquals(drawn.copyOfRange(drawn.size - frameBytes, drawn.size)) shouldBe
      false
  }

  // A preview only seeks a single clip's timeline, so this reads the look's frames that far in on the clock.
  private suspend fun assertAgreesPastSeek(rate: String) {
    val clip = draw.clipOf(160, 90, ::ramp, listOf(LOOK), rate = rate, seconds = 29, container = "mov")
    assertFormsAgree(draw.composition(listOf(clip)), SEEK_FRAMES, at = LONG_SEEK)
  }

  // The tick a frame's exact time gives, written over the frame's number so it can stand in for the look's clock.
  private suspend fun assertReadsExactTicks(
    rate: String,
    tick: String,
  ) {
    val clip = draw.clipOf(160, 90, ::ramp, listOf(LOOK), rate = rate, container = "mov")
    val plan = draw.composition(listOf(clip))
    val want = draw.planesAtExactTicks(plan, TICK_FRAMES, tick)
    withClue("the drawn graph at $rate") {
      draw.formPlanes(plan, TICK_FRAMES, live = false).contentEquals(want) shouldBe true
    }
    withClue("the planes at $rate") {
      draw.formPlanes(plan, TICK_FRAMES, live = true).contentEquals(want) shouldBe true
    }
  }

  // A clip on a microsecond time base with a trimmed one ahead of it puts its frames on exact whole microseconds of the
  // composition, read from the top since a timeline of two clips has no seek.
  private suspend fun assertAgreesPlaced(
    rate: String,
    shift: Duration,
    frames: Int,
  ) {
    val lead =
      draw.clipOf(160, 90, ::ramp, rate = rate, seconds = shift.inWholeSeconds.toInt() + 1, container = "mov")
    val look =
      draw.clipOf(160, 90, ::ramp, listOf(LOOK), rate = rate, container = "mov", timescale = MICROSECONDS)
    val plan = draw.composition(listOf(Clip(lead.source, trim = TimeRange(Duration.ZERO, shift)), look))
    assertFormsAgree(plan, frames)
  }

  private fun clip(
    width: Int,
    height: Int,
    film: Film? = null,
  ) = draw.clipOf(width, height, ::ramp, listOfNotNull(film))

  private fun ramp(fraction: Double): DoubleArray = doubleArrayOf(fraction, 0.5, 1.0 - fraction)

  private companion object {
    const val MICROSECONDS = 1_000_000
    const val SEEK_FRAMES = 24
    const val TICK_FRAMES = 24
    val LOOK = Film()
    val LONG_SEEK = 27_636_000.microseconds
  }
}
