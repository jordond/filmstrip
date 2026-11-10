package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.MAX_LIVE_INPUTS
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.chromaPlanesOf
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.lumaPlaneOf
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the snow ffmpeg lays over a [Vhs] tracking band, on its live planes and on its `geq` graph, against
 * [VhsSignal.snowAt].
 */
class FfmpegVhsSnowMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `snow in the middle of a rolling band lands on the signal`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt)), listOf(SNOWY))
      val frames = listOf(false, true).associateWith { draw.formPlanes(composition, READ_SLOT + 1, live = it) }

      frames.forEach { (live, planes) ->
        val frame = frameOf(planes, WIDTH, HEIGHT)
        withClue("live $live") { assertSnowLands(frame, columns = COLUMNS) }
        bandRows(HEIGHT).map { it / 2 }.distinct().forEach { planeRow ->
          CHROMA_COLUMNS.forEach { column ->
            val want = chromaPlanesOf(SNOWY, tickOf(READ_SLOT), column, planeRow, ::rampAt)
            withClue("live $live chroma row $planeRow column $column") {
              abs(frame.cb(column, planeRow) - want[0]) shouldBeLessThan SNOW_CHROMA_CODES
              abs(frame.cr(column, planeRow) - want[1]) shouldBeLessThan SNOW_CHROMA_CODES
            }
          }
        }
      }
    }

  @Test
  fun `snow on a frame narrower than the raster lands on the signal at its own size`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(SMALL_WIDTH, SMALL_HEIGHT, ::rampAt)
      val composition = draw.composition(listOf(clip), listOf(SNOWY))

      listOf(false, true).forEach { live ->
        val frame = frameOf(draw.formPlanes(composition, READ_SLOT + 1, live = live), SMALL_WIDTH, SMALL_HEIGHT)
        withClue("live $live") { assertSnowLands(frame, columns = 8 until SMALL_WIDTH - 8) }
      }
    }

  @Test
  fun `as many snowy clips as live inputs allow stay on live planes`() =
    runBlocking<Unit> {
      val snowy = draw.clipOf(SMALL_WIDTH, SMALL_HEIGHT, ::rampAt, listOf(SNOWY))
      val one = draw.invocationOf(draw.composition(listOf(snowy))).liveForm.shouldNotBeNull()
      val perClip = one.inputs.count { it.source is InputSource.Live }
      val most = MAX_LIVE_INPUTS / perClip

      perClip shouldBe SNOWY_INPUTS
      val atCap = draw.invocationOf(draw.composition(List(most) { snowy }))
      atCap.liveFallback.shouldBeNull()
      atCap.liveForm.shouldNotBeNull()
      draw.invocationOf(draw.composition(List(most + 1) { snowy })).liveFallback.shouldNotBeNull()
    }

  // Every band row of [frame] at the read slot holds the signal's snow, and enough samples move with it.
  private fun assertSnowLands(
    frame: Yuv420Frame,
    columns: IntRange,
  ) {
    val params = VhsParams.of(SNOWY)
    val tick = tickOf(READ_SLOT)
    val rows = bandRows(frame.height)
    rows.size shouldBeGreaterThan frame.height / 10
    var snowed = 0
    rows.forEach { y ->
      val row = VhsSignal.virtualRow(y, frame.height)
      columns.forEach { column ->
        val x = (column + 0.5) / frame.width
        val snow = VhsSignal.snowAt(SNOWY.seed, tick, row, x, params)
        val want = lumaPlaneOf(SNOWY, tick, column, y, ::rampAt, frame.width, frame.height)
        val clean = lumaPlaneOf(CLEAN, tick, column, y, ::rampAt, frame.width, frame.height)
        if (abs(want - clean) > SNOW_SHOWS_CODES) snowed++
        withClue("row $y column $column snow ${snow.value} at ${snow.weight}") {
          snow.weight shouldBeGreaterThan MID_BAND * SNOW
          abs(frame.luma(column, y) - want) shouldBeLessThan SNOW_LUMA_CODES
        }
      }
    }
    withClue("samples the snow moves") { snowed shouldBeGreaterThan rows.size * columns.count() / 4 }
  }

  private fun bandRows(height: Int): List<Int> {
    val params = VhsParams.of(SNOWY)
    val tick = tickOf(READ_SLOT)
    return (0 until height).filter {
      VhsSignal.trackingWeight(SNOWY.seed, tick, VhsSignal.virtualRow(it, height), params) > MID_BAND
    }
  }

  private fun frameOf(
    frames: ByteArray,
    width: Int,
    height: Int,
  ): Yuv420Frame {
    val bytes = width * height * 3 / 2
    return Yuv420Frame(width, height, frames.copyOfRange(bytes * READ_SLOT, bytes * (READ_SLOT + 1)))
  }

  private companion object {
    const val WIDTH = VHS_WIDTH
    const val HEIGHT = VHS_HEIGHT
    const val SMALL_WIDTH = 320
    const val SMALL_HEIGHT = 180
    const val READ_SLOT = 30
    const val SNOW = 0.6f
    const val MID_BAND = 0.5
    const val SNOW_SHOWS_CODES = 8.0
    const val SNOWY_INPUTS = 3
    val COLUMNS = 16 until WIDTH - 16
    val CHROMA_COLUMNS = 8 until WIDTH / 2 - 8

    // A two second roll sits mid frame at slot 30, a second in.
    val SNOWY = Vhs(tracking = Tracking.Rolling(2.seconds, height = 0.3f, snow = SNOW))
    val CLEAN = Vhs(tracking = Tracking.Rolling(2.seconds, height = 0.3f, snow = 0f))

    // ffmpeg's limit, 8 bit planes against the signal with the snow on. Luma rounds three times past the grade, at the
    // dropout, after the snow's weight comes off and where the weighted snow goes back on, and the weight lands on a
    // 255 step. The most measured is 1.74 in luma and 1.14 in chroma, on ffmpeg 8.0.1 x86_64 and on 9.0.2 arm64 alike.
    const val SNOW_LUMA_CODES = 2.25
    const val SNOW_CHROMA_CODES = 1.5
  }
}
