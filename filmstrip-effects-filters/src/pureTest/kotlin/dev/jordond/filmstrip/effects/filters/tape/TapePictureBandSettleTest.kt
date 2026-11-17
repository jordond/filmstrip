package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.TrackingSettle
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a rolling band eases off from its anchor, which `settle` describes.
 */
class TapePictureBandSettleTest {
  private val anchorTick = TapePictureSignal.tickAt(AT.inWholeSeconds.toDouble())
  private val settleTicks = SETTLE.inWholeSeconds * TICK_RATE

  @Test
  fun `a band with no settle holds full strength`() {
    val params = paramsOf(Tracking.Rolling(PERIOD, at = AT))

    params.trackingSettleTicks shouldBe 0.0
    listOf(0, anchorTick, anchorTick + 50, anchorTick + 100_000).forEach {
      TapePictureSignal.bandSettle(it, params) shouldBe 1.0
      TapePictureSignal.trackingBand(SEED, it, params).strength shouldBe 1.0
    }
  }

  @Test
  fun `a settling band holds full strength up to its anchor`() {
    val params = settling(Easing.EaseInOut)

    listOf(0, anchorTick - 1000, anchorTick - 1, anchorTick).forEach {
      TapePictureSignal.bandSettle(it, params) shouldBe 1.0
    }
  }

  @Test
  fun `a settling band is gone once its settle has run and does not come back`() {
    Easing.entries.forEach { easing ->
      val params = settling(easing)
      val end = anchorTick + ceil(settleTicks).toInt()

      listOf(end, end + 1, end + 360, end + 5 * 360, end + 100_000).forEach {
        TapePictureSignal.bandSettle(it, params) shouldBe 0.0
        TapePictureSignal.trackingBand(SEED, it, params).strength shouldBe 0.0
        (0 until VIRTUAL_ROWS step 17).forEach { row ->
          TapePictureSignal.trackingWeight(SEED, it, row, params) shouldBe 0.0
          TapePictureSignal.rowShift(SEED, it, row, params) shouldBe
            TapePictureSignal.rowShift(SEED, it, row, paramsOf(Tracking.Off))
        }
      }
    }
  }

  @Test
  fun `the middle of the settle is the easing's own middle`() {
    val middle = anchorTick + (settleTicks / 2).toInt()
    val half = (settleTicks / 2).toInt() / settleTicks

    Easing.entries.forEach { easing ->
      TapePictureSignal.bandSettle(middle, settling(easing)) shouldBe
        (1.0 - easing.paced(half.toFloat()) plusOrMinus 1e-6)
    }
    TapePictureSignal.bandSettle(middle, settling(Easing.Linear)) shouldBe (0.5 plusOrMinus 0.01)
    TapePictureSignal.bandSettle(middle, settling(Easing.EaseIn)) shouldBe (0.75 plusOrMinus 0.01)
    TapePictureSignal.bandSettle(middle, settling(Easing.EaseOut)) shouldBe (0.25 plusOrMinus 0.01)
    TapePictureSignal.bandSettle(middle, settling(Easing.EaseInOut)) shouldBe (0.5 plusOrMinus 0.01)
  }

  @Test
  fun `a settle only falls`() {
    Easing.entries.forEach { easing ->
      val params = settling(easing)
      val strengths =
        (anchorTick..anchorTick + settleTicks.toInt() + 5).map {
          TapePictureSignal.bandSettle(
            it,
            params,
          )
        }

      strengths.zipWithNext().forEach { (before, after) -> after shouldBeLessThan before + 1e-12 }
      strengths.first() shouldBe 1.0
      strengths.last() shouldBe 0.0
    }
  }

  @Test
  fun `the slide the noise the colour fade and the snow all ride the strength`() {
    val settled = settling(Easing.Linear)
    val full = paramsOf(Tracking.Rolling(PERIOD, height = HEIGHT, snow = 0.5f, at = AT))
    val tick = anchorTick + (settleTicks * 0.3).toInt()
    val strength = TapePictureSignal.bandSettle(tick, settled)
    val row = (0 until VIRTUAL_ROWS).maxBy { TapePictureSignal.trackingWeight(SEED, tick, it, full) }

    strength shouldBeGreaterThan 0.4
    strength shouldBeLessThan 0.9
    TapePictureSignal.trackingWeight(SEED, tick, row, settled) shouldBe
      (strength * TapePictureSignal.trackingWeight(SEED, tick, row, full) plusOrMinus 1e-12)
    TapePictureSignal.snowAt(SEED, tick, row, 0.3, settled).weight shouldBe
      (strength * TapePictureSignal.snowAt(SEED, tick, row, 0.3, full).weight plusOrMinus 1e-12)
  }

  @Test
  fun `a settle leaves the band's place alone`() {
    val settled = settling(Easing.EaseIn)
    val full = paramsOf(Tracking.Rolling(PERIOD, height = HEIGHT, snow = 0.5f, at = AT))

    listOf(0, anchorTick, anchorTick + 100, anchorTick + 5000).forEach {
      TapePictureSignal.trackingBand(SEED, it, settled).centre shouldBe
        TapePictureSignal.trackingBand(SEED, it, full).centre
    }
  }

  @Test
  fun `an event band and no band never settle`() {
    paramsOf(Tracking.Events()).trackingSettleTicks shouldBe 0.0
    paramsOf(Tracking.Off).trackingSettleTicks shouldBe 0.0
  }

  @Test
  fun `betamax and hi8 settle the band the way vhs does`() {
    val tracking = Tracking.Rolling(PERIOD, at = AT, settle = TrackingSettle(SETTLE, Easing.Linear))

    listOf(Vhs(tracking = tracking), Betamax(tracking = tracking), Hi8(tracking = tracking)).forEach {
      TapePictureSignal.bandSettle(anchorTick + (settleTicks / 2).toInt(), TapePictureParams.of(it)) shouldBe
        (0.5 plusOrMinus 0.01)
    }
  }

  @Test
  fun `a wall preview ignores the settle`() {
    val look =
      Vhs(tracking = Tracking.Rolling(PERIOD, at = AT, settle = TrackingSettle(SETTLE)), clock = TapeClock.Wall)

    val frame = TapePictureFrame.of(look, AT.inWholeSeconds + 2.0, WHOLE, PreviewClock { 1.seconds })

    frame.params.trackingSettleTicks shouldBe 0.0
    TapePictureSignal.bandSettle(frame.tick, frame.params) shouldBe 1.0
    TapePictureFrame.of(look, AT.inWholeSeconds + 2.0, WHOLE, null).params.trackingSettleTicks shouldBe settleTicks
  }

  @Test
  fun `the builder makes the same looks as the constructors`() {
    val settle = TrackingSettle(SETTLE, Easing.EaseOut)
    val built =
      EffectsBuilder()
        .vhs { tracking = rolling(PERIOD, AT, settle) { snow = 0.8f } }
        .betamax { tracking = rolling(at = AT, settle = settle) }
        .hi8 { tracking = rolling(settle = TrackingSettle(SETTLE)) }
        .build()

    built shouldBe
      listOf(
        Vhs(tracking = Tracking.Rolling(PERIOD, snow = 0.8f, at = AT, settle = settle)),
        Betamax(tracking = Tracking.Rolling(at = AT, settle = settle)),
        Hi8(tracking = Tracking.Rolling(settle = TrackingSettle(SETTLE))),
      )
  }

  @Test
  fun `a changed settle is a changed look`() {
    TrackingSettle(SETTLE) shouldBe TrackingSettle(SETTLE, Easing.EaseInOut)
    TrackingSettle(SETTLE) shouldNotBe TrackingSettle(SETTLE + 1.seconds)
    TrackingSettle(SETTLE) shouldNotBe TrackingSettle(SETTLE, Easing.Linear)
    Tracking.Rolling(settle = TrackingSettle(SETTLE)) shouldNotBe Tracking.Rolling()
    Vhs(tracking = Tracking.Rolling(settle = TrackingSettle(SETTLE))) shouldNotBe
      Vhs(tracking = Tracking.Rolling(settle = TrackingSettle(SETTLE, Easing.Linear)))
  }

  @Test
  fun `refuses a settle that is not positive and finite`() {
    shouldThrow<IllegalArgumentException> { TrackingSettle(Duration.ZERO) }
    shouldThrow<IllegalArgumentException> { TrackingSettle((-1).seconds) }
    shouldThrow<IllegalArgumentException> { TrackingSettle(Duration.INFINITE) }
  }

  private fun settling(easing: Easing) =
    paramsOf(Tracking.Rolling(PERIOD, height = HEIGHT, snow = 0.5f, at = AT, settle = TrackingSettle(SETTLE, easing)))

  private fun paramsOf(tracking: Tracking) = TapePictureParams.of(Vhs(tracking = tracking))

  private companion object {
    const val SEED = Vhs.DEFAULT_SEED
    const val HEIGHT = 0.3f
    val PERIOD = 6.seconds
    val AT = 10.seconds
    val SETTLE = 4.seconds
  }
}
