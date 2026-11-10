package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.ffmpeg.support.BYTE
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.lumaCode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.DurationUnit

/**
 * Reads a [Vhs] on a clip beside a clear bar of its canvas against [VhsSignal.pixelAt].
 */
class FfmpegVhsBarEdgeMediaTest {
  private val draw = FilterFrames()

  // The canvas holds clear bars either side of the picture, so the slid read beside one weighs by alpha, and alpha
  // itself mixes the two columns either side. Luma is read since 4:2:0 holds one chroma sample across that edge.
  @Test
  fun `a clip vhs read between the picture and a clear bar weighs by alpha`() =
    runBlocking<Unit> {
      val vhs = Vhs(intensity = INTENSITY)
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val clip = draw.clipOf(RAMP_HEIGHT, RAMP_HEIGHT, { BRIGHT.copyOf() }, listOf(crop, vhs))
      val frame = draw.planesAt(draw.composition(listOf(clip), fill = Fill.Solid(BAR_FILL)), READ_SLOT)
      val params = VhsParams.of(vhs)
      val tick = VhsSignal.tickAt(outputSlotTime(READ_SLOT.toLong(), FILTER_RATE).toDouble(DurationUnit.SECONDS))
      val pictureLeft = (RAMP_WIDTH - RAMP_HEIGHT) / 2
      val pictureRight = pictureLeft + RAMP_HEIGHT
      val fill = listOf(BAR_FILL shr 16 and BYTE, BAR_FILL shr 8 and BYTE, BAR_FILL and BYTE).map { it / FULL }
      val laid = { colour: DoubleArray, alpha: Double ->
        lumaCode(DoubleArray(3) { colour[it] * alpha + fill[it] * (1.0 - alpha) })
      }
      val columns = listOf(pictureLeft, pictureRight).flatMap { it - BOUNDARY_READS..it + BOUNDARY_READS }
      var worst = 0.0
      var slid = 0.0
      (RAMP_HEIGHT / 4 until RAMP_HEIGHT * 3 / 4).forEach { y ->
        val row = VhsSignal.virtualRow(y, RAMP_HEIGHT)
        slid = max(slid, abs(VhsSignal.rowShift(vhs.seed, tick, row, params)) * RAMP_WIDTH)
        columns.forEach { x ->
          val shown =
            VhsSignal.pixelAt((x + 0.5) / RAMP_WIDTH, row, tick, vhs.seed, RAMP_WIDTH, params) { column ->
              if (column in pictureLeft until pictureRight) BRIGHT + 1.0 else DoubleArray(4)
            }
          val miss = abs(frame.luma(x, y).toDouble() - laid(shown, shown[3]))
          worst = max(worst, miss)
        }
      }

      withClue("the tape slid $slid pixels, too little to tell a read") { slid shouldBeGreaterThan MIN_SLIDE }
      withClue("worst $worst") { worst shouldBeLessThan LUMA_CODES }
    }

  private companion object {
    const val READ_SLOT = 30
    const val RAMP_WIDTH = 640
    const val RAMP_HEIGHT = 360
    const val LUMA_CODES = 4.0
    const val BAR_FILL = 0xFF20C040.toInt()
    const val INTENSITY = 0.5f
    const val MIN_SLIDE = 0.1
    const val BOUNDARY_READS = 6
    val BRIGHT = doubleArrayOf(0.745, 0.725, 0.706)
  }
}
