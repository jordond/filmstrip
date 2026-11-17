package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where a rolling band is at the top of the frame, which `at` pins to a composition time.
 */
class TapePictureAnchorTest {
  private val anchor = 7.seconds
  private val anchorTick = TapePictureSignal.tickAt(7.0)

  @Test
  fun `a band with no anchor rolls from tick zero as it always has`() {
    val params = paramsOf(Tracking.Rolling(PERIOD))

    params.trackingAnchorTicks shouldBe 0
    listOf(0, 1, 90, 359, 360, 5000).forEach {
      TapePictureSignal.trackingBand(SEED, it, params).centre shouldBe fract(it / params.trackingPeriodTicks)
    }
  }

  @Test
  fun `the anchor is the tick its time rounds to`() {
    paramsOf(Tracking.Rolling(PERIOD, at = anchor)).trackingAnchorTicks shouldBe anchorTick
    paramsOf(Tracking.Rolling(PERIOD, at = 1.2345678.seconds)).trackingAnchorTicks shouldBe
      TapePictureSignal.tickAt(1.2345678)
  }

  @Test
  fun `the band is at the top on the anchor and a quarter of the way down a quarter period on`() {
    val params = paramsOf(Tracking.Rolling(PERIOD, at = anchor))
    val quarter = (params.trackingPeriodTicks / 4).toInt()
    val half = (params.trackingPeriodTicks / 2).toInt()

    TapePictureSignal.trackingBand(SEED, anchorTick, params).centre shouldBe 0.0
    TapePictureSignal.trackingBand(SEED, anchorTick + quarter, params).centre shouldBe
      (0.25 plusOrMinus 0.005)
    TapePictureSignal.trackingBand(SEED, anchorTick + half, params).centre shouldBe (0.5 plusOrMinus 0.005)
    TapePictureSignal.trackingBand(SEED, anchorTick, params).strength shouldBe 1.0
  }

  @Test
  fun `the heaviest row on the anchor is the top row`() {
    val params = paramsOf(Tracking.Rolling(PERIOD, snow = 0.5f, at = anchor))

    (0 until VIRTUAL_ROWS).maxBy { TapePictureSignal.trackingWeight(SEED, anchorTick, it, params) } shouldBe 0
  }

  @Test
  fun `before the anchor the band keeps rolling on the same period`() {
    val params = paramsOf(Tracking.Rolling(PERIOD, at = anchor))
    val quarter = (params.trackingPeriodTicks / 4).toInt()

    TapePictureSignal.trackingBand(SEED, anchorTick - quarter, params).centre shouldBe (0.75 plusOrMinus 0.005)
    TapePictureSignal.trackingBand(SEED, 0, params).centre shouldBe
      fract(-anchorTick / params.trackingPeriodTicks)
  }

  @Test
  fun `a band is at the top again every period either side of the anchor`() {
    val params = paramsOf(Tracking.Rolling(PERIOD, at = anchor))
    val band = { tick: Int -> TapePictureSignal.trackingBand(SEED, tick, params).centre }

    band(anchorTick + 3 * 360) shouldBe (fract(3 * 360 / params.trackingPeriodTicks))
    band(anchorTick - 360) shouldBe (fract(-360 / params.trackingPeriodTicks))
  }

  @Test
  fun `an anchor moves nothing but the band's place`() {
    val anchored = paramsOf(Tracking.Rolling(PERIOD, at = anchor))
    val plain = paramsOf(Tracking.Rolling(PERIOD))

    anchored.trackingPeriodTicks shouldBe plain.trackingPeriodTicks
    anchored.trackingShift shouldBe plain.trackingShift
    anchored.trackingSnow shouldBe plain.trackingSnow
    anchored.lumaSigma shouldBe plain.lumaSigma
  }

  @Test
  fun `betamax and hi8 anchor the band the way vhs does`() {
    val tracking = Tracking.Rolling(PERIOD, at = anchor)
    val looks = listOf(Vhs(tracking = tracking), Betamax(tracking = tracking), Hi8(tracking = tracking))

    looks.forEach { look ->
      val params = TapePictureParams.of(look)
      params.trackingAnchorTicks shouldBe anchorTick
      TapePictureSignal.trackingBand(SEED, anchorTick, params).centre shouldBe 0.0
    }
  }

  @Test
  fun `an event band and no band have no anchor`() {
    paramsOf(Tracking.Events()).trackingAnchorTicks shouldBe 0
    paramsOf(Tracking.Off).trackingAnchorTicks shouldBe 0
  }

  @Test
  fun `the builder makes the same looks as the constructors`() {
    val built =
      EffectsBuilder()
        .vhs { tracking = rolling(PERIOD, at = anchor) { snow = 0.8f } }
        .betamax { tracking = rolling(at = anchor) }
        .hi8 { tracking = rolling(PERIOD, anchor) }
        .build()

    built shouldBe
      listOf(
        Vhs(tracking = Tracking.Rolling(PERIOD, snow = 0.8f, at = anchor)),
        Betamax(tracking = Tracking.Rolling(at = anchor)),
        Hi8(tracking = Tracking.Rolling(PERIOD, at = anchor)),
      )
  }

  @Test
  fun `a changed anchor is a changed look`() {
    Tracking.Rolling(at = 1.seconds) shouldBe Tracking.Rolling(at = 1.seconds)
    Tracking.Rolling(at = 1.seconds) shouldNotBe Tracking.Rolling(at = 2.seconds)
    Vhs(tracking = Tracking.Rolling(at = 1.seconds)) shouldNotBe Vhs(tracking = Tracking.Rolling())
  }

  @Test
  fun `refuses an anchor that is not finite`() {
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(at = Duration.INFINITE) }
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(at = -Duration.INFINITE) }
  }

  @Test
  fun `refuses an anchor before the first frame`() {
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(at = (-1).seconds) }
    shouldThrow<IllegalArgumentException> { Tracking.Rolling(at = (-1).nanoseconds) }
    Tracking.Rolling(at = Duration.ZERO).at shouldBe Duration.ZERO
  }

  private fun paramsOf(tracking: Tracking) = TapePictureParams.of(Vhs(tracking = tracking))

  private fun fract(value: Double) = value - kotlin.math.floor(value)

  private companion object {
    const val SEED = Vhs.DEFAULT_SEED
    val PERIOD = 6.seconds
  }
}
