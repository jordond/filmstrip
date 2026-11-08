package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_CHROMA_ZERO
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_KB
import dev.jordond.filmstrip.ffmpeg.support.VHS_KR
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_BLACK
import dev.jordond.filmstrip.ffmpeg.support.VHS_LUMA_SPAN
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.assertReadsSignal
import dev.jordond.filmstrip.ffmpeg.support.chromaPlanesOf
import dev.jordond.filmstrip.ffmpeg.support.lumaPlaneOf
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Duration
import io.kotest.matchers.doubles.shouldBeGreaterThan as shouldBeAbove
import io.kotest.matchers.doubles.shouldBeLessThan as shouldBeBelow

/**
 * Reads the frames ffmpeg draws for a [Vhs] straight off the preview pump against [VhsSignal], with no encoder in the
 * way.
 *
 * A chroma plane in 4:2:0 draws its own rows, so a pixel's chroma is read against the virtual row and the column of the
 * sample it comes from, and its luma against its own.
 */
class FfmpegVhsSignalMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `every row lands on the signal at a mid intensity`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val frame = draw.frames(draw.composition(listOf(clip), listOf(vhs)), Duration.ZERO, READ_SLOT + 1).last()

      assertReadsSignal(frame, vhs, WIDTH, HEIGHT, READ_SLOT, ::rampAt)
    }

  @Test
  fun `a smaller preview draws the same tape at its own rows`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val height = HEIGHT / 2
      val width = WIDTH / 2
      val composition = draw.composition(listOf(clip), listOf(vhs))
      val frame = draw.frames(composition, Duration.ZERO, READ_SLOT + 1, ExportSpec(heightPx = height)).last()

      assertReadsSignal(frame, vhs, width, height, READ_SLOT, ::rampAt)
    }

  @Test
  fun `a clip vhs later on the track reads the composition clock`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val plain = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
      val taped = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(vhs))
      val composition = draw.composition(listOf(plain, taped))
      val frame = draw.frames(composition, Duration.ZERO, LATER_SLOT + 1).last()

      assertReadsSignal(frame, vhs, WIDTH, HEIGHT, LATER_SLOT, ::rampAt)
    }

  // gblur is a recursive approximation of a Gaussian, which is ffmpeg's own limit. A step edge shows it where a ramp
  // cannot, against the sigmaFor figures VhsSignal blurs with. The graph's own planes are read, from a clip written
  // straight in those planes, so no RGB conversion sits on either side. Every row is read, since each lands the edge at
  // its own subpixel shift and only some of them show the limit.
  @Test
  fun `gblur lands within its limit of the signal's sigma at a mid intensity`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = 0.5f)
      val colour = draw.clipOfPlanes(WIDTH, HEIGHT, { COLOUR_LUMA }, ::edgeCb, ::edgeCr)
      val grey = draw.clipOfPlanes(WIDTH, HEIGHT, ::edgeLuma, { VHS_CHROMA_ZERO }, { VHS_CHROMA_ZERO })
      val colourFrame = draw.planesAt(draw.composition(listOf(colour), listOf(vhs)), READ_SLOT)
      val greyFrame = draw.planesAt(draw.composition(listOf(grey), listOf(vhs)), READ_SLOT)
      val tick = tickOf(READ_SLOT)
      (0 until HEIGHT / 2).forEach { row ->
        ((WIDTH / 2 - EDGE_SPAN) / 2 until (WIDTH / 2 + EDGE_SPAN) / 2).forEach { column ->
          val want = chromaPlanesOf(vhs, tick, column, row, ::colourAt)
          withClue("colour edge chroma row $row column $column") {
            abs(colourFrame.cb(column, row) - want[0]) shouldBeBelow GBLUR_CHROMA_CODES
            abs(colourFrame.cr(column, row) - want[1]) shouldBeBelow GBLUR_CHROMA_CODES
          }
        }
      }
      (0 until HEIGHT).forEach { y ->
        ((WIDTH / 2 - EDGE_SPAN) until (WIDTH / 2 + EDGE_SPAN)).forEach { column ->
          withClue("grey edge row $y column $column") {
            abs(greyFrame.luma(column, y) - lumaPlaneOf(vhs, tick, column, y, ::greyAt)) shouldBeBelow GBLUR_LUMA_CODES
          }
        }
      }
    }

  // An 8 bit plane rounds the sum of the picture, the noise and the grade, as a shader's output does, so noise under
  // half a code still tips the codes it reaches. Rows of a picture with no change down the column differ by their grain
  // and their jitter, so a read's difference between two rows follows the signal's. A picture sliding through the codes
  // keeps a fraction near every rounding, where a flat one could sit out of the noise's reach.
  @Test
  fun `luma grain under half a code tips the rounded sum`() =
    runBlocking<Unit> {
      val slope = { column: Int -> GRAIN_RAMP_LOW + column / GRAIN_RAMP_PIXELS_PER_CODE }
      val clip = draw.clipOfPlanes(WIDTH, HEIGHT, slope, { VHS_CHROMA_ZERO }, { VHS_CHROMA_ZERO })
      val source = { at: Double ->
        val luma = (planeAt(at, WIDTH, slope) - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
        doubleArrayOf(luma, luma, luma)
      }
      listOf(0.25f, 0.4f).forEach { intensity ->
        val vhs = Vhs(intensity = intensity)
        val frame = draw.planesAt(draw.composition(listOf(clip), listOf(vhs)), READ_SLOT)
        val tick = tickOf(READ_SLOT)
        val want = mutableListOf<Double>()
        val read = mutableListOf<Double>()
        (GRAIN_ROWS step 2).forEach { y ->
          (GRAIN_COLUMNS).forEach { column ->
            val wanted = lumaPlaneOf(vhs, tick, column, y, source) - lumaPlaneOf(vhs, tick, column, y + 1, source)
            // A dropout or the tracking band on one of the rows would swamp the grain.
            if (abs(wanted) < GRAIN_MOST_CODES) {
              want += wanted
              read += (frame.luma(column, y) - frame.luma(column, y + 1)).toDouble()
            }
          }
        }
        withClue("intensity $intensity") {
          correlationOf(want, read) shouldBeAbove GRAIN_CORRELATION
        }
      }
    }

  // Just past a bright edge a super-black pixel sits under its wide blur, so the halo's sum goes below zero there. The
  // sharpest tape keeps the near blur narrowest, so the sum reaches furthest down. A negative lut2 result is not
  // clamped, and ffmpeg 9 writes it as 255. A dropout would whiten a row for its own reason.
  @Test
  fun `a halo over super black stays black`() =
    runBlocking<Unit> {
      val vhs = Vhs(tape = TapeSpeed.SP)
      val params = VhsParams.of(vhs)
      val edge = WIDTH / 2
      val clip =
        draw.clipOfPlanes(WIDTH, HEIGHT, {
          if (it <
            edge
          ) {
            SUPER_BLACK_EDGE
          } else {
            0
          }
        }, { VHS_CHROMA_ZERO }, { VHS_CHROMA_ZERO })
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(vhs)), READ_SLOT)
      val tick = tickOf(READ_SLOT)

      GRAIN_ROWS.forEach { y ->
        val row = VhsSignal.virtualRow(y, HEIGHT)
        val landed = edge + (VhsSignal.rowShift(vhs.seed, tick, row, params) * WIDTH).roundToInt()
        (landed + SUPER_BLACK_FROM until landed + SUPER_BLACK_TO).forEach { column ->
          if (VhsSignal.dropout(vhs.seed, tick, row, (column + 0.5) / WIDTH, params) == 0.0) {
            withClue("row $y column $column") { frame.luma(column, y) shouldBeLessThan SUPER_BLACK_MOST }
          }
        }
      }
    }

  private fun correlationOf(
    a: List<Double>,
    b: List<Double>,
  ): Double {
    val meanA = a.average()
    val meanB = b.average()
    val cross = a.indices.sumOf { (a[it] - meanA) * (b[it] - meanB) }
    return cross / sqrt(a.sumOf { (it - meanA).pow(2) } * b.sumOf { (it - meanB).pow(2) })
  }

  private fun edgeLuma(column: Int): Int = if (column < WIDTH / 2) GREY_LEFT_LUMA else GREY_RIGHT_LUMA

  private fun edgeCb(column: Int): Int = if (column < WIDTH / 4) EDGE_LOW_CHROMA else EDGE_HIGH_CHROMA

  private fun edgeCr(column: Int): Int = if (column < WIDTH / 4) EDGE_HIGH_CHROMA else EDGE_LOW_CHROMA

  // A plane at a fraction of the width the way geq reads it, a straight line between sample centres. A raw step would
  // charge its own subpixel position to the blur.
  private fun planeAt(
    at: Double,
    columns: Int,
    code: (Int) -> Int,
  ): Double {
    val position = at * columns - 0.5
    val left = floor(position).toInt()
    val before = code(left.coerceIn(0, columns - 1))
    val after = code((left + 1).coerceIn(0, columns - 1))
    return before + (after - before) * (position - left)
  }

  // The grey clip's picture as R'G'B', from its luma plane.
  private fun greyAt(at: Double): DoubleArray {
    val luma = (planeAt(at, WIDTH, ::edgeLuma) - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
    return doubleArrayOf(luma, luma, luma)
  }

  // The colour clip's picture as R'G'B', from its chroma planes.
  private fun colourAt(at: Double): DoubleArray {
    val y = (COLOUR_LUMA - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
    val cb = (planeAt(at, WIDTH / 2, ::edgeCb) - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    val cr = (planeAt(at, WIDTH / 2, ::edgeCr) - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
    val r = y + 2.0 * (1.0 - VHS_KR) * cr
    val b = y + 2.0 * (1.0 - VHS_KB) * cb
    return doubleArrayOf(r, (y - VHS_KR * r - VHS_KB * b) / (1.0 - VHS_KR - VHS_KB), b)
  }

  private companion object {
    const val WIDTH = VHS_WIDTH
    const val HEIGHT = VHS_HEIGHT
    const val READ_SLOT = 30
    const val LATER_SLOT = 75
    const val EDGE_SPAN = 16

    // Plane codes either side of the step edges, mid-range so no pixel clamps going through R'G'B'.
    const val COLOUR_LUMA = 110
    const val EDGE_LOW_CHROMA = 100
    const val EDGE_HIGH_CHROMA = 156
    const val GREY_LEFT_LUMA = 158
    const val GREY_RIGHT_LUMA = 93

    // The grain read, on rows and columns clear of the head band and either edge.
    const val SUPER_BLACK_EDGE = 235
    const val SUPER_BLACK_FROM = 2
    const val SUPER_BLACK_TO = 16
    const val SUPER_BLACK_MOST = 64
    const val GRAIN_RAMP_LOW = 40
    const val GRAIN_RAMP_PIXELS_PER_CODE = 4
    val GRAIN_ROWS = 60 until 200
    val GRAIN_COLUMNS = 40 until 600
    const val GRAIN_MOST_CODES = 3.0
    const val GRAIN_CORRELATION = 0.5

    // gblur's limit on a step edge at 16 pixels either side, every row read, in codes of the plane it writes. At this
    // sigma, 0.58 pixels, its recursive filter lands narrower than the Gaussian it asks for, so the two columns on the
    // edge read furthest off. The most measured is 8.8 in luma and 3.9 in chroma on ffmpeg 8.0.1 x86_64, and 7.8 and
    // 3.9 on 9.0.2 arm64.
    const val GBLUR_LUMA_CODES = 9.0
    const val GBLUR_CHROMA_CODES = 4.0
  }
}
