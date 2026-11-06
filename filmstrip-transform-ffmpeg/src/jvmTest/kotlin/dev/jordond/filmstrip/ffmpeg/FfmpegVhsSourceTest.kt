package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_ZERO
import dev.jordond.filmstrip.ffmpeg.support.VHS_COLUMNS_READ
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_KB
import dev.jordond.filmstrip.ffmpeg.support.VHS_KR
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_BLACK
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_CODES
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.VhsFrames
import dev.jordond.filmstrip.ffmpeg.support.assertReadsSignal
import dev.jordond.filmstrip.ffmpeg.support.chromaPlanesOf
import dev.jordond.filmstrip.ffmpeg.support.lumaPlaneOf
import dev.jordond.filmstrip.ffmpeg.support.lumaSampleSignal
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.readRgb
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import dev.jordond.filmstrip.ffmpeg.support.yccOf
import io.kotest.assertions.withClue
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import io.kotest.matchers.doubles.shouldBeGreaterThan as shouldBeAbove
import io.kotest.matchers.doubles.shouldBeLessThan as shouldBeBelow

/**
 * Reads a [Vhs] against [VhsSignal] where a source's format, the frame's edges or a tiny blur could take it off.
 */
class FfmpegVhsSourceTest {
  private val draw = VhsFrames()

  // A clip's chain runs on the source's own format, and the tape's expressions are written in 8 bit limited codes.
  @Test
  fun `a clip vhs lands on the signal from a ten bit source`() =
    runBlocking<Unit> { assertClipScopeReadsSignal(TEN_BIT, "tv") }

  @Test
  fun `a clip vhs lands on the signal from a full range source`() =
    runBlocking<Unit> { assertClipScopeReadsSignal("yuv420p", "pc", VHS_LUMA_CODES + SECOND_ROUNDING) }

  @Test
  fun `a clip vhs lands on the signal from an rgb source`() =
    runBlocking<Unit> { assertClipScopeReadsSignal("gbrp", "pc") }

  // A dropout screens to white, so a tape left on the source's own codes lands it short of white.
  @Test
  fun `a dropout on a ten bit source lands on white`() = runBlocking<Unit> { assertDropoutLandsOnWhite(TEN_BIT, "tv") }

  @Test
  fun `a dropout on a full range source lands on white`() =
    runBlocking<Unit> { assertDropoutLandsOnWhite("yuv420p", "pc") }

  // The tape is black past either edge, so the outermost columns of a flat picture fade towards it. gblur alone holds
  // its edge pixel past the frame and would read them as the picture. The picture's colour is faint enough that no
  // channel clips, which the signal would count and a plane does not.
  @Test
  fun `the outermost columns of a flat picture blur against black`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 1f)
      val flat = draw.clipOfPlanes(WIDTH, HEIGHT, { FLAT_LUMA }, { FLAT_CB }, { FLAT_CR })
      val frame = draw.planesAt(draw.composition(listOf(flat), listOf(vhs)), 0)
      val tick = tickOf(0)
      FLAT_ROWS.forEach { row ->
        outerColumns(WIDTH).forEach { column ->
          withClue("luma row $row column $column") {
            abs(frame.luma(column, row) - lumaPlaneOf(vhs, tick, column, row, ::flatAt)) shouldBeBelow FLAT_LUMA_CODES
          }
        }
        outerColumns(WIDTH / 2).forEach { column ->
          val want = chromaPlanesOf(vhs, tick, column, row / 2, ::flatAt)
          withClue("chroma row ${row / 2} column $column") {
            abs(frame.cb(column, row / 2) - want[0]) shouldBeBelow FLAT_CHROMA_CODES
            abs(frame.cr(column, row / 2) - want[1]) shouldBeBelow FLAT_CHROMA_CODES
          }
        }
      }
    }

  // The head band moves the picture right, so the frame's last columns read tape that was past the frame's edge and
  // blur over more of it. They hold the picture, with no step to black anywhere near.
  @Test
  fun `a row moved right blurs over the picture past the frame's edge`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val flat = draw.clipOfPlanes(WIDTH, HEIGHT, { FLAT_LUMA }, { FLAT_CB }, { FLAT_CR })
      val frame = draw.planesAt(draw.composition(listOf(flat), listOf(vhs)), 0)
      val y = HEIGHT - 1
      val tick = tickOf(0)
      withClue("the head band has to move this row right for this to tell") {
        VhsSignal.rowShift(vhs.seed, tick, VhsSignal.virtualRow(y, HEIGHT), VhsParams.of(vhs)) shouldBeAbove 0.0
      }

      val luma = lumaPlaneOf(vhs, tick, WIDTH - 1, y, ::flatAt)
      withClue("luma against ${frame.luma(WIDTH - 1, y)}") {
        abs(frame.luma(WIDTH - 1, y) - luma) shouldBeBelow VHS_LUMA_CODES
      }
      val chroma = chromaPlanesOf(vhs, tick, WIDTH / 2 - 1, y / 2, ::flatAt)
      abs(frame.cb(WIDTH / 2 - 1, y / 2) - chroma[0]) shouldBeBelow VHS_CHROMA_CODES
      abs(frame.cr(WIDTH / 2 - 1, y / 2) - chroma[1]) shouldBeBelow VHS_CHROMA_CODES
    }

  // These sigmas land under gblur's floor on a frame this narrow, where its filter would darken the frame by a fifth.
  @Test
  fun `a low intensity on a narrow frame blurs at gblur's floor and keeps the picture`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = LOW_INTENSITY)
      val params = VhsParams.of(vhs)
      withClue("the luma sigma has to land under the floor for this to tell") {
        params.lumaSigma * NARROW_WIDTH shouldBeBelow GBLUR_FLOOR
      }
      val clip = draw.clipOf(NARROW_WIDTH, NARROW_HEIGHT, ::rampAt)
      val frame = draw.frames(draw.composition(listOf(clip), listOf(vhs)), Duration.ZERO, READ_SLOT + 1).last()
      val tick = tickOf(READ_SLOT)

      (0 until NARROW_HEIGHT step 4).forEach { y ->
        (4 until NARROW_WIDTH - 4 step 4).forEach { column ->
          val want = yccOf(lumaSampleSignal(vhs, tick, column, y, NARROW_WIDTH, NARROW_HEIGHT, ::rampAt))[0]
          withClue("row $y column $column") {
            abs(yccOf(readRgb(frame, NARROW_WIDTH, column, y))[0] - want) shouldBeBelow VHS_LUMA_CODES
          }
        }
      }
    }

  // Every column the read through R'G'B' leaves out, on both sides of a plane this many samples wide.
  private fun outerColumns(samples: Int): List<Int> {
    val reach = samples / VHS_COLUMNS_READ
    return (0 until reach).toList() + (samples - reach until samples)
  }

  private suspend fun assertClipScopeReadsSignal(
    pixelFormat: String,
    range: String,
    lumaCodes: Double = VHS_LUMA_CODES,
  ) {
    val vhs = Vhs(intensity = 0.5f)
    val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(vhs), pixelFormat, range)
    val frame = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, READ_SLOT + 1).last()

    assertReadsSignal(frame, vhs, WIDTH, HEIGHT, READ_SLOT, ::rampAt, lumaCodes)
  }

  private suspend fun assertDropoutLandsOnWhite(
    pixelFormat: String,
    range: String,
  ) {
    val vhs = Vhs(intensity = 1f)
    val params = VhsParams.of(vhs)
    val (slot, y, x) =
      (0 until LATER_SLOT)
        .firstNotNullOf { slot ->
          (0 until HEIGHT).firstNotNullOfOrNull { y ->
            peakOf(vhs, tickOf(slot), VhsSignal.virtualRow(y, HEIGHT), params)?.let { Triple(slot, y, it) }
          }
        }
    val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(vhs), pixelFormat, range)
    val frame = draw.frames(draw.composition(listOf(clip)), Duration.ZERO, slot + 1).last()
    val column = (x * WIDTH).toInt()

    val wanted = yccOf(lumaSampleSignal(vhs, tickOf(slot), column, y, WIDTH, HEIGHT, ::rampAt))[0]
    withClue("row $y column $column at slot $slot") {
      wanted shouldBeAbove NEAR_WHITE
      abs(yccOf(readRgb(frame, WIDTH, column, y))[0] - wanted) shouldBeBelow VHS_LUMA_CODES
    }
  }

  // Where a dropout on this row is at its fullest, as a fraction of the width, or null when the row holds none.
  private fun peakOf(
    vhs: Vhs,
    tick: Int,
    row: Int,
    params: VhsParams,
  ): Double? =
    (0 until WIDTH)
      .map { (it + 0.5) / WIDTH }
      .firstOrNull { VhsSignal.dropout(vhs.seed, tick, row, it, params) > FULL_DROPOUT }

  // The flat clip's picture as R'G'B', from its plane codes.
  @Suppress("UNUSED_PARAMETER")
  private fun flatAt(at: Double): DoubleArray {
    val y = (FLAT_LUMA - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
    val r = y + 2.0 * (1.0 - VHS_KR) * (FLAT_CR - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    val b = y + 2.0 * (1.0 - VHS_KB) * (FLAT_CB - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    return doubleArrayOf(r, (y - VHS_KR * r - VHS_KB * b) / (1.0 - VHS_KR - VHS_KB), b)
  }

  private companion object {
    const val WIDTH = VHS_WIDTH
    const val HEIGHT = VHS_HEIGHT
    const val READ_SLOT = 30
    const val LATER_SLOT = 75
    const val TEN_BIT = "yuv420p10le"
    const val FULL_DROPOUT = 0.99
    const val NEAR_WHITE = 230.0
    const val LOW_INTENSITY = 0.005f
    const val NARROW_WIDTH = 64
    const val NARROW_HEIGHT = 36
    const val GBLUR_FLOOR = 0.01
    const val FLAT_LUMA = 0x80
    const val FLAT_CB = 112
    const val FLAT_CR = 148
    val FLAT_ROWS = listOf(40, 100, 180, 300)

    // An 8 bit full range source is rounded once more on its way to limited range, half a code at most.
    const val SECOND_ROUNDING = 0.5

    // gblur's limit on the step to black, which grows with the step. At full strength the most measured is 14.1 in luma
    // and 1.9 in chroma on ffmpeg 8.0.1 x86_64. A blur left holding the edge pixel reads 25.6 and 5.0.
    const val FLAT_LUMA_CODES = 18.0
    const val FLAT_CHROMA_CODES = 3.0
  }
}
