package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effects.filters.glitch.GLITCH_ROWS
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test

/**
 * The figures [DigicamSignal] hands every lowering.
 */
class DigicamSignalTest {
  @Test
  fun `the halo blur runs at the frame's own size at every width`() {
    for (width in listOf(640, 1920, 3840)) {
      val plan = DigicamSignal.blurPlan(width)

      plan.factor shouldBe 1
      plan.width shouldBe width
      plan.sigmaPx shouldBe (HALO_SIGMA_FRACTION * width plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `a grey step overshoots on both sides by the halo gain`() {
    val blur = DigicamSignal.blur(STEP_WIDTH, STEP_HEIGHT) { x, _ -> grey(stepCode(x)) }
    val plan = DigicamSignal.blurPlan(STEP_WIDTH)
    val spill = STEP * plan.weights.drop(1).sum()
    val bright = STEP_WIDTH / 2
    val dark = bright - 1

    blur.at(bright, 1)[0] shouldBe (HIGH - spill plusOrMinus TOLERANCE)
    blur.at(dark, 1)[0] shouldBe (LOW + spill plusOrMinus TOLERANCE)
    for (sharpness in listOf(0.5, 1.0)) {
      val params = DigicamParams.at(sharpness, noise = 0.0, clip = 0.0)
      val over = DigicamSignal.sharpened(HIGH, blur.at(bright, 1)[0], params)
      val under = DigicamSignal.sharpened(LOW, blur.at(dark, 1)[0], params)

      over shouldBe (HIGH + HALO_GAIN_MAX * sharpness * spill plusOrMinus TOLERANCE)
      under shouldBe (LOW - HALO_GAIN_MAX * sharpness * spill plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `a flat area keeps its code and a halo never leaves the code range`() {
    val params = DigicamParams.at(sharpness = 1.0, noise = 0.0, clip = 0.0)

    DigicamSignal.sharpened(0.4, 0.4, params) shouldBe 0.4
    DigicamSignal.sharpened(0.9, 0.2, params) shouldBe 1.0
    DigicamSignal.sharpened(0.1, 0.8, params) shouldBe 0.0
  }

  @Test
  fun `clip multiplies a mid code by its gain and stops at white`() {
    val params = DigicamParams.at(sharpness = 0.0, noise = 0.0, clip = 0.5)

    DigicamSignal.clipped(0.5, params) shouldBe (0.5 * (1.0 + CLIP_GAIN_MAX / 2.0) plusOrMinus TOLERANCE)
    DigicamSignal.clipped(0.95, params) shouldBe 1.0
    DigicamSignal.clipped(0.0, params) shouldBe 0.0
  }

  @Test
  fun `a noise cell is its rows of the virtual raster at any height`() {
    DigicamSignal.cellPx(1080) shouldBe NOISE_ROWS
    DigicamSignal.cellPx(2160) shouldBe 2 * NOISE_ROWS
    DigicamSignal.cellPx(720) shouldBe 2
    DigicamSignal.cellPx(360) shouldBe 1
    DigicamSignal.cellPx(90) shouldBe 1
    DigicamSignal.cellPx(2) shouldBe 1
  }

  @Test
  fun `pixels share a cell across its side and no further`() {
    for (height in listOf(360, 1080, 2160)) {
      val side = DigicamSignal.cellPx(height)
      val origin = DigicamSignal.cellOf(5 * side, 3 * side, height)

      DigicamSignal.cellOf(6 * side - 1, 4 * side - 1, height) shouldBe origin
      DigicamSignal.cellOf(6 * side, 3 * side, height) shouldBe origin + 1
      DigicamSignal.cellOf(5 * side, 4 * side, height) shouldBe origin + NOISE_CELLS_ACROSS
    }
  }

  @Test
  fun `chroma noise averages to nothing and spreads over its whole range`() {
    for (axis in 0..1) {
      val draws = (0 until DRAWS).map { DigicamSignal.chromaAt(SEED, 3, it)[axis] }

      abs(draws.average()) shouldBeLessThan MEAN_SLACK
      draws.max() shouldBeGreaterThan 0.9
      draws.min() shouldBeLessThan -0.9
    }
  }

  @Test
  fun `the two chroma axes and two ticks draw apart`() {
    val here = DigicamSignal.chromaAt(SEED, 3, 11)

    here[0] shouldNotBe here[1]
    DigicamSignal.chromaAt(SEED, 4, 11)[0] shouldNotBe here[0]
    DigicamSignal.chromaAt(SEED + 1, 3, 11)[0] shouldNotBe here[0]
    DigicamSignal.chromaAt(SEED, 3, 11).toList() shouldBe here.toList()
  }

  @Test
  fun `the noise weight falls with the square of what is left to white`() {
    val params = DigicamParams.at(sharpness = 0.0, noise = 0.5, clip = 0.0)

    DigicamSignal.noiseWeight(0.0, params) shouldBe (NOISE_MAX / 2.0 plusOrMinus TOLERANCE)
    DigicamSignal.noiseWeight(0.5, params) shouldBe (NOISE_MAX / 8.0 plusOrMinus TOLERANCE)
    DigicamSignal.noiseWeight(1.0, params) shouldBe 0.0
  }

  @Test
  fun `noise moves chroma and keeps the luma`() {
    val params = DigicamParams.at(sharpness = 0.0, noise = 0.5, clip = 0.0)
    val chroma = doubleArrayOf(0.5, -1.0)
    val read = DigicamSignal.noised(0.3, 0.4, 0.5, chroma, params)
    val luma = lumaOf(0.3, 0.4, 0.5)
    val weight = DigicamSignal.noiseWeight(luma, params)

    lumaOf(read[0], read[1], read[2]) shouldBe (luma plusOrMinus TOLERANCE)
    read[0] shouldBe (0.3 + CR_TO_RED * weight * chroma[1] plusOrMinus TOLERANCE)
    read[2] shouldBe (0.5 + CB_TO_BLUE * weight * chroma[0] plusOrMinus TOLERANCE)
  }

  @Test
  fun `noise is larger in the shadows than in the highlights`() {
    val params = DigicamParams.at(sharpness = 0.0, noise = 1.0, clip = 0.0)
    val chroma = doubleArrayOf(1.0, 1.0)
    val shadow = DigicamSignal.noised(0.2, 0.2, 0.2, chroma, params)
    val highlight = DigicamSignal.noised(0.8, 0.8, 0.8, chroma, params)

    (shadow[2] - 0.2) shouldBe ((highlight[2] - 0.8) * 16.0 plusOrMinus TOLERANCE)
    DigicamSignal.noised(1.0, 1.0, 1.0, chroma, params).toList() shouldBe listOf(1.0, 1.0, 1.0)
  }

  @Test
  fun `every tick opens on the whole microsecond nearest its time`() {
    for (tick in listOf(1, 2, 7, 29, 30, 811, 26_973)) {
      val opening = ceil(tick * MICROS / NOISE_RATE - 0.5)

      DigicamSignal.tickAt(opening / MICROS) shouldBe tick
      DigicamSignal.tickAt((opening - 1.0) / MICROS) shouldBe tick - 1
    }
    DigicamSignal.tickAt(0.0) shouldBe 0
  }

  @Test
  fun `a frame time off by under half a microsecond reads the same tick`() {
    val onTick = 27.0 + 1.0 / NOISE_RATE

    DigicamSignal.tickAt(onTick - 4e-7) shouldBe DigicamSignal.tickAt(onTick)
    DigicamSignal.tickAt(onTick + 4e-7) shouldBe DigicamSignal.tickAt(onTick)
  }

  @Test
  fun `pixelAt sharpens then clips then adds the noise of the pixel's cell`() {
    val params = DigicamParams.at(sharpness = 0.5, noise = 0.5, clip = 0.5)
    val rgb = doubleArrayOf(0.5, 0.3, 0.2)
    val blurred = doubleArrayOf(0.45, 0.32, 0.2)
    val pushed = DoubleArray(3) { DigicamSignal.clipped(DigicamSignal.sharpened(rgb[it], blurred[it], params), params) }
    val chroma = DigicamSignal.chromaAt(SEED, 5, DigicamSignal.cellOf(40, 22, 1080))
    val expected = DigicamSignal.noised(pushed[0], pushed[1], pushed[2], chroma, params)

    val read = DigicamSignal.pixelAt(40, 22, 1080, SEED, 5, params, rgb, blurred)

    read.toList() shouldBe expected.toList()
    read[0] shouldNotBe pushed[0]
    DigicamSignal.pixelAt(41, 23, 1080, SEED, 5, params, rgb, blurred).toList() shouldBe expected.toList()
    DigicamSignal.pixelAt(42, 22, 1080, SEED, 5, params, rgb, blurred).toList() shouldNotBe expected.toList()
  }

  @Test
  fun `the cell index stays inside the 24 bits the hash reads at the tallest frame of one pixel cells`() {
    val tallest = (1..GLITCH_ROWS).last { DigicamSignal.cellPx(it) == 1 }

    DigicamSignal.cellOf(NOISE_CELLS_ACROSS - 1, tallest - 1, tallest) shouldBeLessThan (1 shl 24)
  }

  private fun stepCode(x: Int): Double = if (x < STEP_WIDTH / 2) LOW else HIGH

  private fun grey(code: Double): DoubleArray = doubleArrayOf(code, code, code)

  private companion object {
    const val TOLERANCE = 1e-9
    const val SEED = 2001
    const val DRAWS = 20_000
    const val MEAN_SLACK = 0.02
    const val MICROS = 1_000_000.0
    const val STEP_WIDTH = 1920
    const val STEP_HEIGHT = 3
    const val LOW = 0.4
    const val HIGH = 0.6
    const val STEP = HIGH - LOW
  }
}
