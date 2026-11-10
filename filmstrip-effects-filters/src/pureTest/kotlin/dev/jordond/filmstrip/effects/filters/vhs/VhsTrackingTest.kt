package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.edit.EffectsBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The tracking band a [Vhs] draws, its snow, and the builder that describes it.
 */
class VhsTrackingTest {
  @Test
  fun `the default tracking holds the tuned values`() {
    val params = VhsParams.of(Vhs())
    val half = VhsParams.of(Vhs(intensity = 0.5f))

    params.trackingRolls shouldBe false
    params.trackingChance shouldBe 0.2
    params.trackingShift shouldBe 0.02
    params.trackingNoise shouldBe 0.12
    params.trackingHeight shouldBe 0.07
    params.trackingSnow shouldBe 0.6
    half.trackingChance shouldBe 0.05
    half.trackingShift shouldBe 0.01
    half.trackingNoise shouldBe 0.06
    half.trackingSnow shouldBe 0.3
  }

  @Test
  fun `a rolling band halfway through its period sits mid frame`() {
    val params = rolling(snow = 0.5f)
    val tick = VhsSignal.tickAt(PERIOD.inWholeMicroseconds / 2 / 1e6)

    val heaviest = (0 until VIRTUAL_ROWS).maxBy { VhsSignal.trackingWeight(SEED, tick, it, params) }

    heaviest shouldBeInRange VIRTUAL_ROWS / 2 - 1..VIRTUAL_ROWS / 2
    VhsSignal.trackingBand(SEED, tick, params).strength shouldBe 1.0
  }

  @Test
  fun `snow weighs nothing outside the band or at no snow`() {
    val params = rolling(snow = 0.5f)
    val tick = middleTick()

    VhsSignal.snowAt(SEED, tick, 0, 0.4, params).weight shouldBe 0.0
    VhsSignal.snowAt(SEED, tick, VIRTUAL_ROWS / 2, 0.4, rolling(snow = 0f)).weight shouldBe 0.0
  }

  @Test
  fun `half snow mixes the middle of the band half way toward the snow`() {
    val params = rolling(snow = 0.5f)
    val clean = rolling(snow = 0f)
    val tick = middleTick()
    val row = VIRTUAL_ROWS / 2
    val snow = VhsSignal.snowAt(SEED, tick, row, 0.4, params)

    val (luma, cb, _) = VhsSignal.onTape(0.5, 0.1, -0.1, SEED, tick, row, 0.4, params).toList()
    val (cleanLuma, cleanCb, _) = VhsSignal.onTape(0.5, 0.1, -0.1, SEED, tick, row, 0.4, clean).toList()

    snow.weight shouldBe (0.5 * VhsSignal.trackingWeight(SEED, tick, row, params) plusOrMinus 1e-12)
    snow.weight shouldBeGreaterThan 0.45
    val unlifted = (cleanLuma - params.lift) / (1.0 - params.lift)
    luma shouldBe
      (params.lift + (unlifted + (snow.value - unlifted) * snow.weight) * (1.0 - params.lift) plusOrMinus 1e-12)
    cb shouldBe (cleanCb * (1.0 - snow.weight) plusOrMinus 1e-12)
  }

  @Test
  fun `full snow all but drops chroma in the middle of a tall band`() {
    val params = rolling(snow = 1f, height = 0.5f)
    val tick = middleTick()

    val (_, cb, cr) = VhsSignal.onTape(0.5, 0.3, -0.3, SEED, tick, VIRTUAL_ROWS / 2, 0.4, params).toList()

    abs(cb) shouldBeLessThan 1e-3
    abs(cr) shouldBeLessThan 1e-3
  }

  @Test
  fun `snow averages a mid grey over many cells`() {
    val params = rolling(snow = 1f)
    val values = (0 until 2000).map { VhsSignal.snowAt(SEED, it, VIRTUAL_ROWS / 2, 0.37, params).value }

    values.average() shouldBe (0.5 plusOrMinus 0.02)
    values.count { it == 0.0 || it == 1.0 }.toDouble() shouldBeGreaterThan 100.0
  }

  @Test
  fun `no band runs with tracking off or at zero intensity`() {
    val off = VhsParams.at(1.0, TapeSpeed.EP, Tracking.Off)
    val zero = VhsParams.at(0.0, TapeSpeed.EP, Tracking.Rolling(snow = 1f))

    for (params in listOf(off, zero)) {
      (0 until 3 * VhsSignal.trackWindowTicks step 7).forEach {
        VhsSignal.trackingBand(SEED, it, params).strength shouldBe 0.0
      }
      params.trackingShift shouldBe 0.0
      params.trackingNoise shouldBe 0.0
      params.trackingSnow shouldBe 0.0
    }
    val (luma, cb, _) = VhsSignal.onTape(0.5, 0.1, -0.1, SEED, middleTick(), VIRTUAL_ROWS / 2, 0.4, zero).toList()
    luma shouldBe (0.5 plusOrMinus 1e-12)
    cb shouldBe (0.1 plusOrMinus 1e-12)
  }

  @Test
  fun `the builder makes the same Vhs as the constructor`() {
    val built =
      EffectsBuilder()
        .vhs {
          intensity = 0.6f
          tape = TapeSpeed.SP
          seed = 7
          tracking = rolling(period = PERIOD) { snow = 0.8f }
        }.vhs {
          tracking = events(chance = 0.4f) { height = 0.1f }
        }.vhs()
        .build()

    built shouldBe
      listOf(
        Vhs(0.6f, TapeSpeed.SP, 7, Tracking.Rolling(PERIOD, snow = 0.8f)),
        Vhs(tracking = Tracking.Events(chance = 0.4f, height = 0.1f)),
        Vhs(),
      )
  }

  @Test
  fun `a band fades out half its height from its centre`() {
    val params = rolling(snow = 0.5f, height = 0.2f)
    val tick = middleTick()
    val reach = (params.trackingHalfHeight * VIRTUAL_ROWS).toInt()

    params.trackingHalfHeight shouldBe 0.1
    VhsSignal.trackingWeight(SEED, tick, VIRTUAL_ROWS / 2 + reach - 2, params) shouldBeGreaterThan 0.0
    VhsSignal.trackingWeight(SEED, tick, VIRTUAL_ROWS / 2 + reach + 1, params) shouldBe 0.0
  }

  @Test
  fun `refuses tracking values out of range`() {
    shouldThrow<IllegalArgumentException> { Tracking.Events(chance = 1.5f) }
    shouldThrow<IllegalArgumentException> { Tracking.Events(height = 0.01f) }
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(shift = 0.2f) }
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(snow = -0.1f) }
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(period = Duration.ZERO) }
  }

  @Test
  fun `the shader declares every uniform it is handed`() {
    val vhs = Vhs(tracking = Tracking.Rolling(snow = 0.5f))

    VhsShader.uniforms(vhs, 3.0).keys.forEach { VhsShader.FRAGMENT.contains(it) shouldBe true }
    VhsShader.uniforms(vhs, 3.0)["uTrackSnow"] shouldBe 0.5f
  }

  private fun rolling(
    snow: Float,
    height: Float = Tracking.DEFAULT_HEIGHT,
  ) = VhsParams.of(Vhs(tracking = Tracking.Rolling(PERIOD, height = height, snow = snow)))

  private fun middleTick() = VhsSignal.tickAt(PERIOD.inWholeMicroseconds / 2 / 1e6)

  private companion object {
    const val SEED = Vhs.DEFAULT_SEED
    val PERIOD = 6.seconds
  }
}
