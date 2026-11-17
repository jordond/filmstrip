package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.filters.tape.TapePictureRamp
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_ZERO
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_KB
import dev.jordond.filmstrip.ffmpeg.support.VHS_KR
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_BLACK
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_ROWS_READ
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.chromaPlanesOf
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.lumaPlaneOf
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads a [Vhs] that settles over a clip that opens two seconds into the composition, on ffmpeg's live planes and on
 * its drawn graph, against [TapePictureSignal] at the level each frame has: on the frame the settle starts at, in the
 * middle of it, on the frame it ends on, and after it.
 */
class FfmpegVhsSettleMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a settling look reads the signal at the start the middle and the end of its settle and after it`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(), clip(listOf(SETTLING))))

      listOf(false, true).forEach { live ->
        val planes = draw.formPlanes(composition, LAST_SLOT + 1, live = live)
        READ_SLOTS.forEach { slot ->
          withClue("live $live slot $slot") { assertReadsSignal(frameOf(planes, slot), slot) }
        }
      }
    }

  @Test
  fun `the live planes and the drawn graph agree on every frame of a settle`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(), clip(listOf(SETTLING))))

      val drawn = draw.formPlanes(composition, LAST_SLOT + 1, live = false)
      val planes = draw.formPlanes(composition, LAST_SLOT + 1, live = true)

      withClue("${drawn.indices.count { drawn[it] != planes[it] }} of ${drawn.size} samples differ") {
        drawn.contentEquals(planes) shouldBe true
      }
    }

  @Test
  fun `a frame outside the settle is the frame of the constant look at the level it holds`() =
    runBlocking<Unit> {
      val settling = draw.composition(listOf(clip(), clip(listOf(SETTLING))))
      val atStart = draw.composition(listOf(clip(), clip(listOf(Vhs(intensity = START)))))
      val atEnd = draw.composition(listOf(clip(), clip(listOf(Vhs(intensity = END)))))

      val ramped = draw.formPlanes(settling, LAST_SLOT + 1, live = false)
      val first = draw.formPlanes(atStart, LAST_SLOT + 1, live = false)
      val last = draw.formPlanes(atEnd, LAST_SLOT + 1, live = false)

      assertSameFrame(ramped, first, FIRST_SLOT)
      listOf(END_SLOT, AFTER_SLOT, LAST_SLOT).forEach { assertSameFrame(ramped, last, it) }
    }

  @Test
  fun `a settle on a composition counts from the composition's first frame`() =
    runBlocking<Unit> {
      val settling = draw.composition(listOf(clip(), clip()), listOf(Vhs(intensity = END, settle = SETTLE)))
      val atEnd = draw.composition(listOf(clip(), clip()), listOf(Vhs(intensity = END)))
      val atStart = draw.composition(listOf(clip(), clip()), listOf(Vhs(intensity = START)))

      val ramped = draw.formPlanes(settling, END_SLOT + 1, live = false)

      assertSameFrame(ramped, draw.formPlanes(atStart, 1, live = false), 0)
      assertSameFrame(ramped, draw.formPlanes(atEnd, END_SLOT + 1, live = false), END_SLOT - 2 * FILTER_RATE)
    }

  // A step in the middle of a flat picture blurs as wide as the sigma the frame's level gives, so a blur at another
  // level than the frame's reads off the signal where the step is.
  @Test
  fun `a step in a flat picture blurs at the sigma of the level the frame has`() =
    runBlocking<Unit> {
      val step =
        draw.clipOfPlanes(VHS_WIDTH, VHS_HEIGHT, {
          if (it <
            VHS_WIDTH / 2
          ) {
            STEP_LOW
          } else {
            STEP_HIGH
          }
        }, { FLAT_CB }, { FLAT_CR }, listOf(SETTLING_FROM_START))
      val planes = draw.formPlanes(draw.composition(listOf(step)), FILTER_RATE + 1, live = false)
      val look = SETTLING_FROM_START
      val ramp = TapePictureRamp.of(look, TimeRange(0.seconds)).shouldNotBeNull()
      val columns = (VHS_WIDTH / 2 - STEP_REACH..VHS_WIDTH / 2 + STEP_REACH step 2).toList()
      val worst = mutableMapOf<Int, Double>()
      var spread = 0.0

      listOf(0, 7, FILTER_RATE / 2, FILTER_RATE - 7, FILTER_RATE).forEach { slot ->
        val frame = frameOf(planes, slot)
        val tick = tickOf(slot)
        val params = look.pictureParams(ramp.levelAt(tick), Duration.ZERO)
        val wrong = look.pictureParams(if (ramp.levelAt(tick) > ramp.to) ramp.to else ramp.from, Duration.ZERO)
        EDGE_ROWS.forEach { y ->
          columns.forEach { column ->
            val want = lumaPlaneOf(look, tick, column, y, ::stepAt, params = params)
            val miss = abs(frame.luma(column, y) - want)
            worst[slot] = maxOf(worst[slot] ?: 0.0, miss)
            spread = maxOf(spread, abs(want - lumaPlaneOf(look, tick, column, y, ::stepAt, params = wrong)))
            withClue("slot $slot row $y column $column") { miss shouldBeLessThan STEP_LUMA_CODES }
          }
        }
      }
      withClue("the blur at another level has to read off the signal for this to tell, worst $worst") {
        spread shouldBeGreaterThan STEP_SENSITIVE_CODES
      }
    }

  // A clip with bars beside its picture reads exactly beside the picture's edges, and reads exactly everywhere once
  // an earlier look has softened those bars.
  @Test
  fun `a settling look beside a clear bar is the constant look at its ends`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val bright = doubleArrayOf(0.745, 0.725, 0.706)
      listOf(listOf(crop), listOf(crop, Vhs(intensity = 0.5f))).forEach { before ->
        val make = { look: Vhs ->
          val clip = draw.clipOf(BAR_SIDE, BAR_SIDE, { bright.copyOf() }, before + look)
          draw.composition(listOf(clip), fill = Fill.Solid(BAR_FILL))
        }
        val ramped = draw.formPlanes(make(SETTLING_FROM_START), END_AT + 1, live = false)
        val first = draw.formPlanes(make(Vhs(intensity = START)), 1, live = false)
        val last = draw.formPlanes(make(Vhs(intensity = END)), END_AT + 1, live = false)

        // The middle of the settle is the constant look at the level it has there, to the taps against `gblur`, and a
        // look 0.3 of the level away is further, with a look before it as well as without.
        val level = RAMP_FROM_START.levelAt(tickOf(MID_AT))
        val at: suspend (Double) -> ByteArray = { strength ->
          draw.formPlanes(make(Vhs(intensity = strength.toFloat())), MID_AT + 1, live = false)
        }
        val middle = meanGap(ramped, at(level), MID_AT)
        val away = meanGap(ramped, at(level + AWAY), MID_AT)

        withClue("${before.size} effects before the look") {
          assertSameFrame(ramped, first, 0)
          assertSameFrame(ramped, last, END_AT)
          withClue("middle $middle, a tenth of the level away $away") {
            middle shouldBeLessThan MIDDLE_GAP
            away shouldBeGreaterThan 2 * middle
          }
        }
      }
    }

  // A preview only seeks a single clip's timeline, so the settle is the clip's own and the seek lands half way through.
  @Test
  fun `a preview opened inside the settle draws the frames an export draws there`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(listOf(SETTLING_FROM_START))))
      val whole = draw.formPlanes(composition, 2 * FILTER_RATE, live = false)
      val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
      val seek = FILTER_RATE / 2

      listOf(false, true).forEach { live ->
        val opened = draw.formPlanes(composition, 6, live = live, at = (seek.toDouble() / FILTER_RATE).seconds)
        repeat(6) { k ->
          val want = whole.copyOfRange(bytes * (seek + k), bytes * (seek + k + 1))
          withClue("live $live frame $k") {
            opened.copyOfRange(bytes * k, bytes * (k + 1)).contentEquals(want) shouldBe true
          }
        }
      }
    }

  // The mean gap in codes between frame [slot] of the two runs of frames, over the whole of it.
  private fun meanGap(
    a: ByteArray,
    b: ByteArray,
    slot: Int,
  ): Double {
    val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
    return (0 until bytes)
      .sumOf {
        abs(
          (a[bytes * slot + it].toInt() and 0xFF) - (b[bytes * slot + it].toInt() and 0xFF),
        )
      }.toDouble() / bytes
  }

  private fun assertSameFrame(
    ramped: ByteArray,
    constant: ByteArray,
    slot: Int,
  ) {
    val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
    val a = ramped.copyOfRange(bytes * slot, bytes * (slot + 1))
    val b = constant.copyOfRange(bytes * slot, bytes * (slot + 1))
    withClue("slot $slot, ${a.indices.count { a[it] != b[it] }} of ${a.size} samples differ") {
      a.contentEquals(b) shouldBe true
    }
  }

  // The luma and the chroma of every sample read against the signal at the level of the frame's tick.
  private fun assertReadsSignal(
    frame: Yuv420Frame,
    slot: Int,
  ) {
    val tick = tickOf(slot)
    val params = SETTLING.pictureParams(RAMP.levelAt(tick), OPENS)
    val rows = (0 until VHS_HEIGHT step VHS_HEIGHT / VHS_ROWS_READ) + (VHS_HEIGHT - 1)
    val columns = (VHS_WIDTH / READ_COLUMNS until VHS_WIDTH step VHS_WIDTH / READ_COLUMNS)
    rows.forEach { y ->
      columns.forEach { column ->
        val luma = lumaPlaneOf(SETTLING, tick, column, y, ::rampAt, params = params)
        withClue("row $y column $column") { abs(frame.luma(column, y) - luma) shouldBeLessThan VHS_LUMA_CODES }
      }
      (CHROMA_STEP until VHS_WIDTH / 2 step CHROMA_STEP).forEach { column ->
        val want = chromaPlanesOf(SETTLING, tick, column, y / 2, ::rampAt, params)
        withClue("chroma row ${y / 2} column $column") {
          abs(frame.cb(column, y / 2) - want[0]) shouldBeLessThan VHS_CHROMA_CODES
          abs(frame.cr(column, y / 2) - want[1]) shouldBeLessThan VHS_CHROMA_CODES
        }
      }
    }
  }

  // The picture of the clip the step is read on, as the straight R'G'B' of its codes.
  private fun stepAt(at: Double): DoubleArray {
    val y = ((if (at < 0.5) STEP_LOW else STEP_HIGH) - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
    val r = y + 2.0 * (1.0 - VHS_KR) * (FLAT_CR - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    val b = y + 2.0 * (1.0 - VHS_KB) * (FLAT_CB - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    return doubleArrayOf(r, (y - VHS_KR * r - VHS_KB * b) / (1.0 - VHS_KR - VHS_KB), b)
  }

  private fun clip(effects: List<Vhs> = emptyList()) = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::rampAt, effects)

  private fun frameOf(
    frames: ByteArray,
    slot: Int,
  ): Yuv420Frame {
    val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
    return Yuv420Frame(VHS_WIDTH, VHS_HEIGHT, frames.copyOfRange(bytes * slot, bytes * (slot + 1)))
  }

  private companion object {
    const val START = 1f
    const val END = 0.35f
    val SETTLE = TapeSettle(START, 1.seconds, Easing.Linear)

    // The look's slot opens two seconds in and its settle runs one second, so it starts on slot 60, is half through
    // on slot 75 and ends on slot 90.
    const val FIRST_SLOT = 2 * FILTER_RATE
    const val END_SLOT = 3 * FILTER_RATE
    const val AFTER_SLOT = END_SLOT + 5
    const val LAST_SLOT = END_SLOT + 15
    const val READ_COLUMNS = 16
    const val CHROMA_STEP = 40
    val READ_SLOTS = listOf(FIRST_SLOT, FIRST_SLOT + 1, 67, 75, 83, END_SLOT - 1, END_SLOT, AFTER_SLOT)
    val SETTLING = Vhs(intensity = END, tracking = Tracking.Events(), settle = SETTLE)

    // The same look on a scope that opens with the composition, which settles over the first second.
    val SETTLING_FROM_START = Vhs(intensity = END, settle = SETTLE)
    const val END_AT = FILTER_RATE
    const val MID_AT = FILTER_RATE / 2
    const val AWAY = 0.3
    const val MIDDLE_GAP = 1.0
    val RAMP_FROM_START = TapePictureRamp.of(SETTLING_FROM_START, TimeRange(Duration.ZERO)).shouldNotBeNull()
    const val BAR_SIDE = 360
    const val BAR_FILL = 0xFF20C040.toInt()
    val EDGE_ROWS = listOf(40, 100, 180, 300)
    const val EDGE_COLUMNS = 6
    const val STEP_LOW = 64
    const val STEP_HIGH = 192
    const val STEP_REACH = 16

    // ffmpeg's own limit on a step, which is widest on the gblur ends of the settle, where the sigma is under a pixel.
    // Measured at 9.7 there and 7.0 inside the settle, against the 42.6 codes a blur at the wrong level is off by.
    const val STEP_LUMA_CODES = 12.0
    const val STEP_SENSITIVE_CODES = 3 * STEP_LUMA_CODES
    const val FLAT_CB = 112
    const val FLAT_CR = 148
    val OPENS = 2.seconds
    val RAMP = TapePictureRamp.of(SETTLING, TimeRange(OPENS)).shouldNotBeNull()
  }
}
