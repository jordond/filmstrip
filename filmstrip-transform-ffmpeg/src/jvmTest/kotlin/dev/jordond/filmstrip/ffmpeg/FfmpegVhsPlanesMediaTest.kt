package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.planesAtExactTicks
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * A VHS drawn from live planes reads no differing sample against its `geq` graph.
 */
class FfmpegVhsPlanesMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a clip vhs at 1080p`() =
    runBlocking<Unit> { assertFormsAgree(draw.composition(listOf(clip(1920, 1080, Vhs()))), frames = 8) }

  @Test
  fun `a frame narrower than the raster`() =
    runBlocking<Unit> { assertFormsAgree(draw.composition(listOf(clip(320, 180, Vhs()))), frames = 24) }

  @Test
  fun `a composition vhs across two clips`() =
    runBlocking<Unit> {
      val plain = clip(640, 360)
      assertFormsAgree(draw.composition(listOf(plain, plain), listOf(Vhs())), frames = 66)
    }

  @Test
  fun `a clip that opens at two seconds reads its own ticks`() =
    runBlocking<Unit> {
      assertFormsAgree(draw.composition(listOf(clip(640, 360), clip(640, 360, Vhs()))), frames = 68)
    }

  @Test
  fun `a preview window reads the ticks it opened on`() =
    runBlocking<Unit> {
      assertFormsAgree(draw.composition(listOf(clip(640, 360, Vhs()))), frames = 10, at = 1200.milliseconds)
    }

  @Test
  fun `a 23_976 clip reads one tick on every half tick it lands on`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(320, 180, ::ramp, listOf(Vhs()), rate = "24000/1001", seconds = 3, container = "mov")
      assertFormsAgree(draw.composition(listOf(clip)), frames = 70)
    }

  @Test
  fun `a 23_976 clip seeked past frame 200 reads one tick`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(320, 180, ::ramp, listOf(Vhs()), rate = "24000/1001", seconds = 10, container = "mov")
      assertFormsAgree(draw.composition(listOf(clip)), frames = 8, at = (199 * 1001 * 1000 / 24).microseconds)
    }

  @Test
  fun `a 30 fps clip seeked past frame 1250 reads one tick`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(320, 180, ::ramp, listOf(Vhs()), seconds = 43, container = "mov")
      assertFormsAgree(draw.composition(listOf(clip)), frames = 6, at = (1248 * 1_000_000L / 30).microseconds)
    }

  @Test
  fun `a 23_976 clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("24000/1001") }

  @Test
  fun `a 29_97 clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("30000/1001") }

  @Test
  fun `a 59_94 clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("60000/1001") }

  @Test
  fun `a 25 fps clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("25") }

  @Test
  fun `a 30 fps clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("30") }

  @Test
  fun `a 24 fps clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("24") }

  @Test
  fun `a 60 fps clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("60") }

  @Test
  fun `a 50 fps clip past a long seek`() = runBlocking<Unit> { assertAgreesPastSeek("50") }

  @Test
  fun `a 30 fps clip with a frame 3_003 us under a tick opening`() =
    runBlocking<Unit> { assertAgreesPlaced("30", 27_636_000.microseconds, frames = 832) }

  @Test
  fun `a 29_97 clip with a frame 3 us under a tick opening`() =
    runBlocking<Unit> { assertAgreesPlaced("30000/1001", 27_969_633.microseconds, frames = 842) }

  @Test
  fun `a clip on another matrix stays on the drawn graph by name`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(640, 360, ::ramp, listOf(Vhs()), onSmpteC = true)
      val invocation = draw.invocationOf(draw.composition(listOf(clip)))

      invocation.liveForm.shouldBeNull()
      invocation.liveFallback.shouldNotBeNull() shouldContain "not tagged BT.709"
    }

  @Test
  fun `a bt709 clip runs on planes`() =
    runBlocking<Unit> {
      draw.invocationOf(draw.composition(listOf(clip(640, 360, Vhs())))).liveForm.shouldNotBeNull()
    }

  @Test
  fun `a 24 fps clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("24", "floor(N*5994/2400+0.5)") }

  @Test
  fun `a 30 fps clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("30", "floor(N*5994/3000+0.5)") }

  @Test
  fun `a 60 fps clip reads the tick its exact time gives`() =
    runBlocking<Unit> { assertReadsExactTicks("60", "floor(N*5994/6000+0.5)") }

  private suspend fun assertFormsAgree(
    plan: Composition,
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
    vhs: Vhs? = null,
  ) = draw.clipOf(width, height, ::ramp, listOfNotNull(vhs))

  private fun ramp(fraction: Double): DoubleArray = doubleArrayOf(fraction, 0.5, 1.0 - fraction)

  private companion object {
    const val MICROSECONDS = 1_000_000
    const val SEEK_FRAMES = 24
    const val TICK_FRAMES = 24
    val LOOK = Vhs()
    val LONG_SEEK = 27_636_000.microseconds
  }
}
