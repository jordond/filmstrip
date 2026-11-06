package dev.jordond.filmstrip.effects.filters.vhs

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * The numbers every lowering reads, at the middle of their ranges.
 */
class VhsSignalTest {
  @Test
  fun `hash24 matches its golden table`() {
    VhsSignal.hash24(1984, 1, 3000, 240) shouldBe 0xEBA0F3
    VhsSignal.hash24(1984, 6, 3600, 13553) shouldBe 0x9AEE56
    VhsSignal.hash24(7, 2, 0, 0) shouldBe 0x7840A1
    VhsSignal.hash24(123456, 12, 215784, 479) shouldBe 0x0BF85C
    VhsSignal.hash24(1984, 9, 59, 300) shouldBe 0x9BE378
  }

  @Test
  fun `hash24 spreads evenly over a run of rows and ticks`() {
    val buckets = IntArray(BUCKETS)
    for (tick in 3000 until 3100) {
      for (row in 0 until 480) {
        val hash = VhsSignal.hash24(Vhs.DEFAULT_SEED, 6, tick, row)
        hash shouldBeInRange 0..0xFFFFFF
        buckets[(VhsSignal.unorm(hash) * BUCKETS).toInt()]++
      }
    }

    val expected = 100 * 480 / BUCKETS
    buckets.forEach { abs(it - expected) shouldBeLessThan expected / 8 }
  }

  @Test
  fun `tickAt rounds to the nearest field`() {
    VhsSignal.tickAt(1.5) shouldBe 90
    VhsSignal.tickAt(60.0) shouldBe 3596
  }

  @Test
  fun `virtualRow counts rows from the top on the 480 line raster`() {
    VhsSignal.virtualRow(540, 1080) shouldBe 240
    VhsSignal.virtualRow(100, 720) shouldBe 66
    VhsSignal.virtualRow(1079, 1080) shouldBe 479
  }

  @Test
  fun `sigmaFor lands three decibels down on the cutoff`() {
    VhsSignal.sigmaFor(1.4) shouldBe (0.1325 / (1.4 * 52.0) plusOrMinus 1e-12)
  }

  @Test
  fun `params at half intensity halve the strengths and quarter the chances`() {
    val half = VhsParams.at(0.5, TapeSpeed.EP)
    val full = VhsParams.at(1.0, TapeSpeed.EP)

    half.lumaSigma shouldBe (full.lumaSigma / 2 plusOrMinus 1e-12)
    half.chromaSigma shouldBe (full.chromaSigma / 2 plusOrMinus 1e-12)
    half.haloGain shouldBe (full.haloGain / 2 plusOrMinus 1e-12)
    half.jitter shouldBe (full.jitter / 2 plusOrMinus 1e-12)
    half.headShift shouldBe (full.headShift / 2 plusOrMinus 1e-12)
    half.lift shouldBe (full.lift / 2 plusOrMinus 1e-12)
    half.dropoutChance shouldBe (full.dropoutChance / 4 plusOrMinus 1e-12)
    half.trackingChance shouldBe (full.trackingChance / 4 plusOrMinus 1e-12)
  }

  @Test
  fun `shapes stay fixed as intensity changes`() {
    // A window with no tracking band at any intensity, so every term left in the shift is a linear strength.
    val tick =
      (0 until 100).first { window ->
        VhsSignal.unorm(VhsSignal.hash24(Vhs.DEFAULT_SEED, PASS_TRACK_EVENT, window, 0)) >= TRACK_CHANCE_AT_ONE
      } * VhsSignal.trackWindowTicks + 30
    val row = VIRTUAL_ROWS - VhsSignal.headRows / 2
    val shiftAt = { intensity: Double ->
      VhsSignal.rowShift(Vhs.DEFAULT_SEED, tick, row, VhsParams.at(intensity, TapeSpeed.EP))
    }

    shiftAt(0.5) shouldBe (shiftAt(1.0) / 2 plusOrMinus 1e-12)
    VhsSignal.headRows shouldBe 8
    VhsSignal.trackWindowTicks shouldBe 240
  }

  @Test
  fun `params at zero intensity leave a mid grey alone`() {
    val params = VhsParams.at(0.0, TapeSpeed.EP)

    val (luma, cb, cr) = VhsSignal.onTape(0.5, 0.1, -0.1, Vhs.DEFAULT_SEED, 3000, 240, 0.5, params).toList()

    luma shouldBe (0.5 plusOrMinus 1e-12)
    cb shouldBe (0.1 plusOrMinus 1e-12)
    cr shouldBe (-0.1 plusOrMinus 1e-12)
  }

  @Test
  fun `the shader carries the signal's constants`() {
    val source = VhsShader.FRAGMENT

    source.startsWith("#version 300 es") shouldBe true
    source.contains("(k * ${HASH_M1}u)") shouldBe true
    source.contains("(h * ${HASH_M4}u)") shouldBe true
  }

  private companion object {
    const val BUCKETS = 16
    const val TRACK_CHANCE_AT_ONE = 0.2
  }
}
