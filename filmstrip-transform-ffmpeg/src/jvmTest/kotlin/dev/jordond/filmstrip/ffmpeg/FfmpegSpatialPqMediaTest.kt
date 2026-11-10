package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.BEFORE_ZERO
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.clipOfPicture
import dev.jordond.filmstrip.ffmpeg.support.fragmentFrame
import dev.jordond.filmstrip.ffmpeg.support.fragmentOf
import dev.jordond.filmstrip.ffmpeg.support.graySource
import dev.jordond.filmstrip.ffmpeg.support.lumaClipOf
import dev.jordond.filmstrip.ffmpeg.support.pqClipOfLuma
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the ten-bit frames ffmpeg draws for a [GaussianBlur] on a kept PQ grade, against [blurPlane] run on the SDR
 * signal, and checks a contained picture keeps its bars exactly the fill.
 */
class FfmpegSpatialPqMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a blur on a kept PQ grade runs on the sdr signal`() =
    runBlocking<Unit> {
      val clip =
        draw.pqClipOfLuma(WIDTH, HEIGHT, {
          if (it <
            WIDTH / 2
          ) {
            codeOfNits(LIGHT_NITS)
          } else {
            codeOfNits(DIM_NITS)
          }
        })
      val plain = draw.tenBitLumaAt(draw.composition(listOf(clip)), 0, COLOR_READ_HDR_SPEC)
      val blurred =
        draw.tenBitLumaAt(
          draw.composition(listOf(clip), listOf(GaussianBlur(SIGMA))),
          0,
          COLOR_READ_HDR_SPEC,
        )
      val opaque = DoubleArray(plain.size) { 1.0 }

      val signal = DoubleArray(plain.size) { sdrSignalFromNits(nitsOfCode(plain[it])) }
      val expected =
        blurPlane(
          signal,
          opaque,
          WIDTH,
          HEIGHT,
          SIGMA.toDouble(),
        ).map { codeOfNits(nitsFromSdrSignal(it)) }
      val onPq = blurPlane(DoubleArray(plain.size) { plain[it].toDouble() }, opaque, WIDTH, HEIGHT, SIGMA.toDouble())

      val row = HEIGHT / 2 * WIDTH
      var worst = 0.0
      var apart = 0.0
      for (index in row until row + WIDTH) {
        worst = maxOf(worst, abs(blurred[index] - expected[index]).toDouble())
        apart = maxOf(apart, abs(onPq[index] - expected[index]))
      }
      withClue("a blur of the PQ codes lands at most $apart codes from the sdr signal's, too close to tell apart") {
        apart shouldBeGreaterThan 2 * KEPT_BLUR_CODES
      }
      withClue("the kept grade's blur is $worst ten-bit codes off the shared plan on the sdr signal") {
        worst shouldBeLessThanOrEqual KEPT_BLUR_CODES
      }
    }

  // The grade's picture blurs by the shared plan on the sdr signal up to its edge, with the bars counting for nothing,
  // and every bar sample reads the code the fill lands on with no blur at all.
  @Test
  fun `a kept PQ composition blur keeps a contained picture to its edge and the bars exactly the fill`() =
    runBlocking<Unit> {
      val clip =
        draw.pqClipOfLuma(WIDTH, HEIGHT, {
          if (it <
            WIDTH / 2
          ) {
            codeOfNits(LIGHT_NITS)
          } else {
            codeOfNits(DIM_NITS)
          }
        })
      val plain =
        draw.tenBitLumaAt(draw.composition(listOf(clip), listOf(contain()), Fill.Solid(SLATE)), 0, COLOR_READ_HDR_SPEC)
      val blurred =
        draw.tenBitLumaAt(
          draw.composition(listOf(clip), listOf(contain(), GaussianBlur(SIGMA)), Fill.Solid(SLATE)),
          0,
          COLOR_READ_HDR_SPEC,
        )
      val height = plain.size / CONTAINED_WIDTH
      val pictureRows = BAR_ROWS until height - BAR_ROWS
      val alpha = DoubleArray(plain.size) { if (it / CONTAINED_WIDTH in pictureRows) 1.0 else 0.0 }
      val signal = DoubleArray(plain.size) { sdrSignalFromNits(nitsOfCode(plain[it])) }
      val expected =
        blurPlane(signal, alpha, CONTAINED_WIDTH, height, SIGMA.toDouble()).map { codeOfNits(nitsFromSdrSignal(it)) }

      var worst = 0.0
      var worstAt = 0
      for (index in plain.indices) {
        if (alpha[index] == 0.0) {
          withClue("luma at ${index % CONTAINED_WIDTH}, ${index / CONTAINED_WIDTH} in the bar") {
            blurred[index] shouldBe plain[index]
          }
        } else if (abs(blurred[index] - expected[index]) > worst) {
          worst = abs(blurred[index] - expected[index]).toDouble()
          worstAt = index
        }
      }
      val x = worstAt % CONTAINED_WIDTH
      val y = worstAt / CONTAINED_WIDTH
      withClue(
        "the kept grade's blur is $worst ten-bit codes off the shared plan on the sdr signal at $x, $y, reading " +
          "${blurred[worstAt]} for ${expected[worstAt]} from ${plain[worstAt]}",
      ) {
        worst shouldBeLessThanOrEqual KEPT_BLUR_CODES
      }
    }

  // A clip with non-square pixels reaches the fragment as it is stored, half the width it shows at, and is blurred and
  // vignetted there with no resampling before the backend widens it.

  private fun contain(): Crop = Crop(CONTAINED, fit = Fit.Contain)

  private fun codeOfNits(nits: Double): Int = (TEN_BIT_BLACK + TEN_BIT_RANGE * pqSignalFromNits(nits)).roundToInt()

  private fun nitsOfCode(code: Int): Double = nitsFromPqSignal((code - TEN_BIT_BLACK) / TEN_BIT_RANGE)

  private companion object {
    const val WIDTH = 384
    const val HEIGHT = 216
    const val SIGMA = 0.05f

    // The same on a kept grade in ten-bit codes, about one eight bit code, with the tables each way. Measured at 4 on
    // 8.0.1.
    const val KEPT_BLUR_CODES = 5.0

    // An 8 by 9 canvas over the clip is 192 by 216, and its picture fills the width at 108 rows tall, so a bar of 54
    // rows runs above and below it.
    val CONTAINED = AspectRatio(8, 9)
    const val CONTAINED_WIDTH = 192
    const val BAR_ROWS = 54
    const val SLATE = 0xFF2F4F6F.toInt()

    // Below reference white, so neither side reaches the ceiling the tables clamp at.
    const val LIGHT_NITS = 150.0
    const val DIM_NITS = 8.0
    const val TEN_BIT_BLACK = 64.0
    const val TEN_BIT_RANGE = 876.0
  }
}
