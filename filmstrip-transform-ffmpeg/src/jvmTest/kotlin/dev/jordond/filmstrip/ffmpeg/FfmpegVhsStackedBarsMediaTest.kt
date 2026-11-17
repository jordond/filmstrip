package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test

/**
 * Reads a tape look stacked after another over a clip with bars beside its picture. The look reads the picture where
 * the picture is, whatever strength it has, and not the fill of the bars.
 */
class FfmpegVhsStackedBarsMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a tape look after another draws the picture of a clip with bars and not the fill`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val bright = doubleArrayOf(0.745, 0.725, 0.706)
      val centreOf: suspend (List<EffectSpec>) -> Yuv420Frame = { effects ->
        val clip = draw.clipOf(SIDE, SIDE, { bright.copyOf() }, effects)
        val frames = draw.formPlanes(draw.composition(listOf(clip), fill = Fill.Solid(FILL)), 1, live = false)
        Yuv420Frame(VHS_WIDTH, VHS_HEIGHT, frames.copyOf(VHS_WIDTH * VHS_HEIGHT * 3 / 2))
      }

      listOf(0.2f, 0.9f).forEach { strength ->
        val plain = centreOf(listOf(crop, Vhs(intensity = strength))).luma(VHS_WIDTH / 2, VHS_HEIGHT / 2)
        val stacked =
          centreOf(listOf(crop, Vhs(intensity = 0.5f), Vhs(intensity = strength))).luma(VHS_WIDTH / 2, VHS_HEIGHT / 2)

        withClue("strength $strength, plain $plain stacked $stacked") {
          abs(stacked - plain).toDouble() shouldBeLessThan CLOSE
          abs(stacked - FILL_LUMA).toDouble() shouldBeGreaterThan CLOSE
        }
      }
    }

  // The alpha the exact read writes is the source's at the shifted column, so the edge of the picture moves with each
  // row's shift as the plain read's does. A look too faint to shift the edge itself goes first, so the stacked edge is
  // the plain one, and an alpha that was constant or not shifted puts it in the same place on every row.
  @Test
  fun `the edge of the picture after another look moves with the rows' shift as it does without one`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio(16, 9), fit = Fit.Contain)
      val bright = doubleArrayOf(0.745, 0.725, 0.706)
      val frameOf: suspend (List<EffectSpec>) -> Yuv420Frame = { effects ->
        val clip = draw.clipOf(SIDE, SIDE, { bright.copyOf() }, effects)
        val frames = draw.formPlanes(draw.composition(listOf(clip), fill = Fill.Solid(LIGHT)), 1, live = false)
        Yuv420Frame(VHS_WIDTH, VHS_HEIGHT, frames.copyOf(VHS_WIDTH * VHS_HEIGHT * 3 / 2))
      }
      val plain = frameOf(listOf(crop, Vhs(intensity = 0.9f)))
      val stacked = frameOf(listOf(crop, Vhs(intensity = FAINT), Vhs(intensity = 0.9f)))
      // The fill is light and the picture is not, and a bar is black where nothing under it has alpha, so a dark pixel
      // beside the picture is a column read with an alpha that has not moved with the colour.
      val half = (plain.luma(VHS_WIDTH / 2, VHS_HEIGHT / 2) + LIGHT_LUMA) / 2
      val edgeOf = { frame: Yuv420Frame, y: Int ->
        (0 until VHS_WIDTH / 2).first { frame.luma(it, y) < half }
      }
      val darkOf = { frame: Yuv420Frame, y: Int -> (0 until VHS_WIDTH).count { frame.luma(it, y) < DARK } }
      val rows = (0 until VHS_HEIGHT step ROW_STEP) + (VHS_HEIGHT - HEAD_ROWS until VHS_HEIGHT)

      withClue("the bars beside the picture are the fill, after the look as without it") {
        listOf(plain, stacked).forEach { frame ->
          rows.forEach { y -> abs(frame.luma(BAR_COLUMN, y) - LIGHT_LUMA).toDouble() shouldBeLessThan CLOSE_BAR }
        }
      }
      val spread = rows.map { edgeOf(plain, it) }.let { it.max() - it.min() }
      withClue("the plain edge has to move with the rows for this to tell, by $spread") {
        spread.toDouble() shouldBeGreaterThan MOVES
      }
      rows.forEach { y ->
        withClue("row $y") {
          abs(edgeOf(stacked, y) - edgeOf(plain, y)).toDouble() shouldBeLessThan EDGE_PIXELS
          abs(darkOf(stacked, y) - darkOf(plain, y)).toDouble() shouldBeLessThan EDGE_PIXELS
        }
      }
    }

  private companion object {
    const val FAINT = 0.02f
    const val LIGHT = 0xFFF0F0F0.toInt()
    const val LIGHT_LUMA = 222.0
    const val DARK = 120
    const val ROW_STEP = 6
    const val HEAD_ROWS = 8
    const val BAR_COLUMN = 5
    const val CLOSE_BAR = 3.0
    const val MOVES = 4.0
    const val EDGE_PIXELS = 1.5
    const val SIDE = 360
    const val FILL = 0xFF20C040.toInt()
    const val FILL_LUMA = 144.0
    const val CLOSE = 12.0
  }
}
