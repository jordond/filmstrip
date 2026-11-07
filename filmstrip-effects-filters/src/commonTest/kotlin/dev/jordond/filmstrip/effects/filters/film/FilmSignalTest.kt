package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The numbers every film lowering reads, at the middle of their ranges.
 */
class FilmSignalTest {
  @Test
  fun `grainTickAt counts 24 a second and floors between them`() {
    FilmSignal.grainTickAt(0.0) shouldBe 0
    FilmSignal.grainTickAt(1.5) shouldBe 36
    FilmSignal.grainTickAt(1.51) shouldBe 36
    FilmSignal.grainTickAt(1.54) shouldBe 36
    FilmSignal.grainTickAt(1.5417) shouldBe 37
  }

  @Test
  fun `grainTickAt holds a frame under an opening on the tick before it`() {
    FilmSignal.grainTickAt(0.041663) shouldBe 0
    FilmSignal.grainTickAt(0.041666) shouldBe 0
    FilmSignal.grainTickAt(0.041667) shouldBe 1
  }

  @Test
  fun `a tick opens on the whole microsecond nearest its exact time`() {
    for (tick in listOf(1, 2, 3, 37, 577, 100_000, 1_499_999)) {
      val stamp = ceil(tick * GRAIN_CLOCK_BASE / GRAIN_TICK_RATE - GRAIN_CLOCK_ROUNDING)
      FilmSignal.grainTickAt(stamp / GRAIN_CLOCK_BASE) shouldBe tick
      FilmSignal.grainTickAt((stamp - 1) / GRAIN_CLOCK_BASE) shouldBe tick - 1
    }
  }

  @Test
  fun `a 24 fps frame reads one tick of its own with no repeat and no skip`() {
    val ticks = List(240) { frame -> FilmSignal.grainTickAt(frame / GRAIN_TICK_RATE) }

    ticks shouldBe List(240) { it }
  }

  @Test
  fun `grainTickAt reads the tick a frame's exact time gives at every common rate`() {
    for ((num, den) in listOf(24L to 1L, 30L to 1L, 48L to 1L, 60L to 1L, 24_000L to 1001L)) {
      val wrong =
        (0L until 100_000L).filter { frame ->
          FilmSignal.grainTickAt(frame * den.toDouble() / num).toLong() != frame * den * 24 / num
        }

      withClue("at $num/$den") { wrong shouldBe emptyList() }
    }
  }

  @Test
  fun `grainCell is one pixel a cell at 1080p and the same place at any height`() {
    FilmSignal.grainCell(100, 200, 1920, 1080) shouldBe 200 * 1920 + 100
    FilmSignal.grainCell(200, 400, 3840, 2160) shouldBe FilmSignal.grainCell(100, 200, 1920, 1080)
    FilmSignal.grainCell(201, 401, 3840, 2160) shouldBe FilmSignal.grainCell(100, 200, 1920, 1080)
    FilmSignal.grainCell(50, 100, 960, 540) shouldBe FilmSignal.grainCell(100, 200, 1920, 1080)
    FilmSignal.grainCell(100, 100, 1280, 720) shouldBe 150 * 1920 + 150
  }

  @Test
  fun `grainCell keeps cells square and inside the raster off 16 by 9`() {
    val cellOnPortrait = FilmSignal.grainCell(16, 16, 1080, 1920)
    cellOnPortrait shouldBe 9 * 1920 + 9
    FilmSignal.grainCell(1079, 1919, 1080, 1920) shouldBe 1079 * 1920 + 606
    FilmSignal.grainCell(100, 100, 1000, 1000) shouldBe 108 * 1920 + 108
  }

  @Test
  fun `grainAt is zero mean over a run of cells and ticks`() {
    val values = grid()

    abs(values.average()) shouldBeLessThan 0.01
    values.forEach {
      it shouldBeGreaterThanOrEqual -1.0
      it shouldBeLessThan 1.0
    }
  }

  @Test
  fun `grainAt spreads as the sum of two uniform draws`() {
    val values = grid()
    val mean = values.average()
    val spread = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)

    // A triangle on -1 to 1 has a variance of a sixth, where a single uniform draw on it would have a third.
    spread shouldBe (sqrt(1.0 / 6.0) plusOrMinus 0.01)
    (values.count { abs(it) < 0.5 } / values.size.toDouble()) shouldBe (0.75 plusOrMinus 0.02)
  }

  @Test
  fun `grainWeight peaks in the midtones over its floor`() {
    FilmSignal.grainWeight(0.5) shouldBe (1.0 plusOrMinus 1e-12)
    FilmSignal.grainWeight(0.25) shouldBe (0.75 plusOrMinus 1e-12)
    FilmSignal.grainWeight(0.75) shouldBe (0.75 plusOrMinus 1e-12)
    FilmSignal.grainWeight(0.02) shouldBe (0.25 plusOrMinus 1e-12)
    FilmSignal.grainWeight(1.0) shouldBe (0.25 plusOrMinus 1e-12)
  }

  @Test
  fun `halationResponse is the smoothstep of luma times the tint`() {
    val middle = (HALATION_LOW + HALATION_HIGH) / 2

    val response = FilmSignal.halationResponse(middle, middle, middle)

    response[0] shouldBe (0.5 * HALATION_TINT_RED plusOrMinus 1e-9)
    response[1] shouldBe (0.5 * HALATION_TINT_GREEN plusOrMinus 1e-9)
    response[2] shouldBe (0.5 * HALATION_TINT_BLUE plusOrMinus 1e-9)
    FilmSignal.halationResponse(0.5, 0.5, 0.5).toList() shouldBe listOf(0.0, 0.0, 0.0)
    FilmSignal.halationResponse(1.0, 1.0, 1.0)[0] shouldBe (HALATION_TINT_RED plusOrMinus 1e-9)
  }

  @Test
  fun `wideBlurPlan keeps its sigma at most 8 pixels at mid-range widths`() {
    for (width in listOf(640, 1280, 1920, 2560, 3840)) {
      val plan = FilmSignal.wideBlurPlan(HALATION_SIGMA, width)

      plan.sigmaPx shouldBeLessThanOrEqualTo WIDE_BLUR_MAX_SIGMA
      plan.taps shouldBeLessThanOrEqualTo WIDE_BLUR_MAX_TAPS
      plan.weights.size shouldBe plan.taps + 1
      plan.taps shouldBe kotlin.math.ceil(WIDE_BLUR_SUPPORT * plan.sigmaPx).toInt()
    }
  }

  @Test
  fun `wideBlurPlan lands on the requested sigma as a fraction of the frame`() {
    for (width in listOf(640, 1280, 1920, 2560, 3840)) {
      val plan = FilmSignal.wideBlurPlan(HALATION_SIGMA, width)

      val variance = 2.0 * plan.weights.indices.sumOf { it * it * plan.weights[it] }
      val effective = sqrt(variance) / plan.width

      // Cutting a Gaussian off at 3 sigma takes about 1.4 percent off its second moment's root.
      effective shouldBe (HALATION_SIGMA plusOrMinus HALATION_SIGMA * 0.02)
    }
  }

  @Test
  fun `wideBlurPlan weights sum to one with each side counted`() {
    val weights = FilmSignal.wideBlurPlan(HALATION_SIGMA, 1920).weights

    (weights[0] + 2.0 * weights.drop(1).sum()) shouldBe (1.0 plusOrMinus 1e-12)
  }

  @Test
  fun `wideBlurPlan scales the blurred frame by its factor`() {
    val plan = FilmSignal.wideBlurPlan(HALATION_SIGMA, 1920)

    plan.factor shouldBe 3
    plan.width shouldBe 640
    plan.heightOf(1080) shouldBe 360
  }

  @Test
  fun `params at full hold the tuned strengths`() {
    val full = FilmParams.at(1.0, 1.0)

    full.grainStrength shouldBe (0.07 plusOrMinus 1e-12)
    full.halationGain shouldBe (0.35 plusOrMinus 1e-12)
  }

  @Test
  fun `params at a half halve each strength in its own control`() {
    val full = FilmParams.at(1.0, 1.0)

    FilmParams.at(0.5, 1.0).grainStrength shouldBe (full.grainStrength / 2 plusOrMinus 1e-12)
    FilmParams.at(0.5, 1.0).halationGain shouldBe (full.halationGain plusOrMinus 1e-12)
    FilmParams.at(1.0, 0.5).halationGain shouldBe (full.halationGain / 2 plusOrMinus 1e-12)
    FilmParams.at(1.0, 0.5).grainStrength shouldBe (full.grainStrength plusOrMinus 1e-12)
  }

  @Test
  fun `pixelAt weighs the grain on the frame after its halation`() {
    val params = FilmParams.at(0.5, 0.5)
    val cell = FilmSignal.grainCell(960, 540, 1920, 1080)
    val blurred = doubleArrayOf(1.0, 0.25, 0.08)
    val code = 0.3

    val pixel = FilmSignal.pixelAt(code, code, code, blurred, cell, TICK, params)

    val glow = FilmSignal.halated(code, code, code, blurred, params)
    val weighedAfter = FilmSignal.grainWeight(lumaOf(glow[0], glow[1], glow[2]))
    val weighedBefore = FilmSignal.grainWeight(code)
    val grain = params.grainStrength * FilmSignal.grainAt(cell, TICK)
    pixel[0] shouldBe (glow[0] + grain * weighedAfter plusOrMinus 1e-12)
    pixel[1] shouldBe (glow[1] + grain * weighedAfter plusOrMinus 1e-12)
    pixel[2] shouldBe (glow[2] + grain * weighedAfter plusOrMinus 1e-12)
    (abs(weighedAfter - weighedBefore) > 0.01) shouldBe true
  }

  @Test
  fun `halated clamps to one and grain clamps to the code range`() {
    val params = FilmParams.at(1.0, 1.0)

    FilmSignal.halated(0.95, 0.5, 0.5, doubleArrayOf(1.0, 0.0, 0.0), params)[0] shouldBe 1.0
    val cell = (0 until 100).first { FilmSignal.grainAt(it, TICK) < -0.5 }
    FilmSignal.grained(0.0, 0.0, 0.0, cell, TICK, params).toList() shouldBe listOf(0.0, 0.0, 0.0)
  }

  private fun grid(): List<Double> =
    buildList {
      for (tick in 3000 until 3020) {
        for (cell in 100_000 until 101_000) add(FilmSignal.grainAt(cell, tick))
      }
    }

  private companion object {
    const val TICK = 3000
  }
}
