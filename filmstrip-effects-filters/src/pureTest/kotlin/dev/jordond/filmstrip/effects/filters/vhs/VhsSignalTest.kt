package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.HASH_M1
import dev.jordond.filmstrip.effects.filters.internal.HASH_M4
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test

/**
 * The numbers every lowering reads, at the middle of their ranges.
 */
class VhsSignalTest {
  @Test
  fun `hash24 matches its golden table`() {
    FilterNoise.hash24(1984, 1, 3000, 240) shouldBe 0xEBA0F3
    FilterNoise.hash24(1984, 6, 3600, 13553) shouldBe 0x9AEE56
    FilterNoise.hash24(7, 2, 0, 0) shouldBe 0x7840A1
    FilterNoise.hash24(123456, 12, 215784, 479) shouldBe 0x0BF85C
    FilterNoise.hash24(1984, 9, 59, 300) shouldBe 0x9BE378
  }

  @Test
  fun `hash24 spreads evenly over a run of rows and ticks`() {
    val buckets = IntArray(BUCKETS)
    for (tick in 3000 until 3100) {
      for (row in 0 until 480) {
        val hash = FilterNoise.hash24(Vhs.DEFAULT_SEED, 6, tick, row)
        hash shouldBeInRange 0..0xFFFFFF
        buckets[(FilterNoise.unorm(hash) * BUCKETS).toInt()]++
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
  fun `a frame on a half tick reads the tick it opens`() {
    VhsSignal.tickAt(200.0 / 24.0) shouldBe 500
    VhsSignal.tickAt(1250.0 / 30.0) shouldBe 2498
    VhsSignal.tickAt(500.0 / 12.0) shouldBe 2498
    VhsSignal.tickAt(1000.0 / 120.0) shouldBe 500
  }

  @Test
  fun `a frame at a rate dividing the clock reads the tick it sits on or opens`() {
    val wrong =
      (0L until 100_000L).filter { frame ->
        VhsSignal.tickAt(frame * 3 / TICK_RATE).toLong() != frame * 3 ||
          VhsSignal.tickAt(frame * 2 / TICK_RATE).toLong() != frame * 2 ||
          VhsSignal.tickAt(frame / TICK_RATE).toLong() != frame ||
          VhsSignal.tickAt(frame / (2 * TICK_RATE)).toLong() != (frame + 1) / 2
      }

    wrong shouldBe emptyList()
  }

  @Test
  fun `tickAt reads the tick a frame's exact time gives at the whole rates`() {
    for (rate in listOf(24L, 25L, 30L, 48L, 50L, 60L, 120L)) {
      val wrong =
        (0L until 100_000L).filter { frame ->
          VhsSignal.tickAt(frame.toDouble() / rate).toLong() != (frame * 5994 * 2 + 100 * rate) / (200 * rate)
        }

      withClue("at $rate") { wrong shouldBe emptyList() }
    }
  }

  @Test
  fun `tickAt holds the earlier tick up to the microsecond before a plane's stamp`() {
    VhsSignal.tickAt(27.669333) shouldBe 1658
    VhsSignal.tickAt(28.003) shouldBe 1678
  }

  @Test
  fun `a tick opens on the whole microsecond nearest its exact time`() {
    for (tick in listOf(1, 3, 1499, 1659, 1679, 4496, 100_000, 1_499_999)) {
      val stamp = ceil((tick - TICK_ROUNDING) * TICK_CLOCK_BASE / TICK_RATE - TICK_CLOCK_ROUNDING)
      VhsSignal.tickAt(stamp / TICK_CLOCK_BASE) shouldBe tick
      VhsSignal.tickAt((stamp - 1) / TICK_CLOCK_BASE) shouldBe tick - 1
    }
  }

  @Test
  fun `a 23_976 clip reads the later tick only where the clock's rate has it under half a microsecond`() {
    val earlier = { frame: Int -> (frame * 5 - 1) / 2 }
    val onLaterTick = (1 until 100_000 step 2).filter { VhsSignal.tickAt(frame2398(it)) != earlier(it) }

    onLaterTick shouldBe listOf(1, 3, 5, 9, 11, 17)
    VhsSignal.tickAt(frame2398(1)) shouldBe 3
    VhsSignal.tickAt(frame2398(19)) shouldBe earlier(19)
  }

  private fun frame2398(frame: Int) = frame * 1001.0 / 24000.0

  @Test
  fun `tickAt has no slack under a tick's opening`() {
    val halfTick = 499.5 / TICK_RATE

    VhsSignal.tickAt(halfTick - 1e-6) shouldBe 499
    VhsSignal.tickAt(halfTick - 1e-3) shouldBe 499
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
        FilterNoise.unorm(FilterNoise.hash24(Vhs.DEFAULT_SEED, PASS_TRACK_EVENT, window, 0)) >= TRACK_CHANCE_AT_ONE
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

  @Test
  fun `pixelAt reads alpha between a clear bar and the picture at the shifted position`() {
    val params = VhsParams.of(Vhs())
    val tick = 300
    val row = 200
    val texel = { column: Int -> if (column < WIDTH / 2) DoubleArray(4) else doubleArrayOf(0.5, 0.5, 0.5, 1.0) }
    // Three tenths of the way from the bar's last centre to the picture's first, once the row's slide is taken off.
    val x = (WIDTH / 2 - 0.5 + 0.3) / WIDTH + VhsSignal.rowShift(Vhs.DEFAULT_SEED, tick, row, params)

    val read = VhsSignal.pixelAt(x, row, tick, Vhs.DEFAULT_SEED, WIDTH, params, texel)

    read.size shouldBe 4
    read[3] shouldBe (0.3 plusOrMinus 1e-9)
    VhsSignal.pixelAt(x, row, tick, Vhs.DEFAULT_SEED, WIDTH, params) { texel(it).copyOf(3) }.size shouldBe 3
  }

  private companion object {
    const val WIDTH = 640
    const val BUCKETS = 16
    const val TRACK_CHANCE_AT_ONE = 0.2
  }
}
