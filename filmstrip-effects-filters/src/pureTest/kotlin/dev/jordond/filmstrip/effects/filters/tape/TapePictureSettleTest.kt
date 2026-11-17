package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a [Vhs] settles from a weaker or stronger strength to its own, which `settle` describes.
 */
class TapePictureSettleTest {
  private val opens = 10.seconds
  private val slot = TimeRange(opens, 30.seconds)
  private val startTick = TapePictureSignal.tickAt(10.0)
  private val overTicks = OVER.inWholeSeconds * TICK_RATE

  @Test
  fun `a look that does not settle draws the strengths it always has`() {
    val look = Vhs(intensity = 0.7f)

    listOf(0.0, 3.0, 10.0, 99.0).forEach { seconds ->
      figuresOf(TapePictureFrame.of(look, seconds, slot, null).params) shouldBe figuresOf(TapePictureParams.of(look))
    }
    TapePictureRamp.of(look, slot).shouldBeNull()
  }

  @Test
  fun `a look that settles to where it starts has no ramp`() {
    TapePictureRamp.of(Vhs(intensity = 0.4f, settle = TapeSettle(0.4f, OVER)), slot).shouldBeNull()
  }

  @Test
  fun `the level is the starting level on the scope's first frame and before it`() {
    Easing.entries.forEach { easing ->
      val ramp = rampOf(easing)

      ramp.startTick shouldBe startTick
      listOf(0, startTick - 100, startTick - 1, startTick).forEach { ramp.levelAt(it) shouldBe FROM.toDouble() }
    }
  }

  @Test
  fun `the level is the look's own intensity once the settle has run`() {
    Easing.entries.forEach { easing ->
      val ramp = rampOf(easing)

      listOf(ramp.endTick, ramp.endTick + 1, ramp.endTick + 5000).forEach { ramp.levelAt(it) shouldBe TO.toDouble() }
      ramp.levelAt(ramp.endTick - 1) shouldNotBe TO.toDouble()
    }
  }

  @Test
  fun `the middle of the settle is the easing's own middle between the two levels`() {
    val middle = startTick + (overTicks / 2).toInt()
    val progress = (overTicks / 2).toInt() / overTicks

    Easing.entries.forEach { easing ->
      val eased = easing.paced(progress.toFloat()).toDouble()
      val want = FROM * (1.0 - eased) + TO * eased

      rampOf(easing).levelAt(middle) shouldBe (want plusOrMinus 1e-12)
    }
    rampOf(Easing.Linear).levelAt(middle) shouldBe ((FROM + TO) / 2.0 plusOrMinus 1e-2)
    rampOf(Easing.EaseIn).levelAt(middle) shouldBeGreaterThan rampOf(Easing.Linear).levelAt(middle)
  }

  @Test
  fun `the level moves from one end to the other and never leaves them`() {
    val low = Vhs(intensity = 0.2f, settle = TapeSettle(0.9f, OVER, Easing.EaseInOut))
    val ramp = TapePictureRamp.of(low, slot).shouldNotBeNull()
    val levels = (startTick..ramp.endTick).map { ramp.levelAt(it) }

    levels.zipWithNext().forEach { (before, after) -> after shouldBeLessThan before + 1e-12 }
    levels.forEach { it shouldBeGreaterThan 0.2 - 1e-12 }
    levels.forEach { it shouldBeLessThan 0.9 + 1e-12 }
  }

  @Test
  fun `a look at a tick of its settle draws the strengths of the constant look at that level`() {
    val settling = Vhs(intensity = TO, settle = TapeSettle(FROM, OVER, Easing.EaseInOut))
    val ramp = rampOf(Easing.EaseInOut)

    listOf(
      startTick,
      startTick + 20,
      startTick + (overTicks / 2).toInt(),
      ramp.endTick - 1,
      ramp.endTick,
      ramp.endTick + 90,
    ).forEach { tick ->
      val seconds = tick / TICK_RATE
      val frame = TapePictureFrame.of(settling, seconds, slot, null)
      val level = ramp.levelAt(frame.tick)

      figuresOf(frame.params) shouldBe figuresOf(settling.pictureParams(level, slot.start))
    }
  }

  @Test
  fun `every figure follows the one level`() {
    val settling = Vhs(intensity = TO, settle = TapeSettle(FROM, OVER, Easing.Linear))
    val middle = startTick + (overTicks / 2).toInt()
    val level = rampOf(Easing.Linear).levelAt(middle)
    val here = figuresOf(TapePictureFrame.of(settling, middle / TICK_RATE, slot, null).params)
    val unit = figuresOf(settling.pictureParams(1.0, slot.start))

    here.keys.forEach { name ->
      val scale = if (name in SQUARED) level * level else level
      if (name in FOLLOWING) here.getValue(name) shouldBe (unit.getValue(name) * scale plusOrMinus 1e-12)
    }
    level shouldBeGreaterThan TO.toDouble()
    level shouldBeLessThan FROM.toDouble()
  }

  @Test
  fun `a clip that opens late counts its settle from its own first frame`() {
    val look = Vhs(intensity = TO, settle = TapeSettle(FROM, OVER, Easing.Linear))
    val late = TimeRange(opens, 30.seconds)
    val early = TimeRange(Duration.ZERO, 30.seconds)
    val first = TapePictureFrame.of(look, 10.0, late, null)
    val half = TapePictureFrame.of(look, 10.0 + OVER.inWholeSeconds / 2.0, late, null)

    figuresOf(first.params) shouldBe figuresOf(look.pictureParams(FROM.toDouble(), slot.start))
    figuresOf(half.params) shouldBe
      figuresOf(look.pictureParams(TapePictureRamp.of(look, late).shouldNotBeNull().levelAt(half.tick), slot.start))
    half.params.lumaSigma shouldBe ((FROM + TO) / 2.0 * TapePictureParams.of(Vhs()).lumaSigma plusOrMinus 1e-4)
    figuresOf(TapePictureFrame.of(look, 10.0, early, null).params) shouldBe figuresOf(TapePictureParams.of(look))
  }

  @Test
  fun `a settle on a rolling band moves the strength and leaves the band's place`() {
    val tracking = Tracking.Rolling(6.seconds, at = opens)
    val settling = Vhs(intensity = TO, tracking = tracking, settle = TapeSettle(FROM, OVER, Easing.Linear))
    val plain = Vhs(intensity = TO, tracking = tracking)
    val middle = startTick + (overTicks / 2).toInt()

    val at = TapePictureFrame.of(settling, middle / TICK_RATE, slot, null)
    val band = TapePictureSignal.trackingBand(settling.seed, at.tick, at.params)

    band.centre shouldBe
      TapePictureSignal.trackingBand(plain.seed, at.tick, TapePictureParams.of(plain, slot.start)).centre
    at.params.trackingAnchorTicks shouldBe TapePictureParams.of(plain, slot.start).trackingAnchorTicks
  }

  @Test
  fun `a wall preview settles on the frame's own time and draws its pattern from the wall`() {
    val look = Vhs(intensity = TO, settle = TapeSettle(FROM, OVER, Easing.Linear), clock = TapeClock.Wall)
    val ramp = TapePictureRamp.of(look, slot).shouldNotBeNull()
    val clock = PreviewClock { 3.seconds }

    listOf(9.0, 10.0, 10.0 + OVER.inWholeSeconds / 2.0, 10.0 + OVER.inWholeSeconds, 20.0).forEach { seconds ->
      val frame = TapePictureFrame.of(look, seconds, slot, clock)
      val level = ramp.levelAt(TapePictureSignal.tickAt(seconds))

      frame.tick shouldBe TapePictureSignal.tickAt(3.0)
      figuresOf(frame.params) shouldBe figuresOf(look.pictureParams(level, null))
    }
    figuresOf(TapePictureFrame.of(look, 10.0, slot, clock).params) shouldBe
      figuresOf(TapePictureFrame.of(look, 10.0, slot, null).params)
  }

  @Test
  fun `betamax and hi8 settle the way vhs does`() {
    val settle = TapeSettle(FROM, OVER, Easing.Linear)
    val middle = startTick + (overTicks / 2).toInt()

    listOf(
      Vhs(intensity = TO, settle = settle),
      Betamax(intensity = TO, settle = settle),
      Hi8(intensity = TO, settle = settle),
    ).forEach { look ->
      val level = TapePictureRamp.of(look, slot).shouldNotBeNull().levelAt(middle)

      figuresOf(TapePictureFrame.of(look, middle / TICK_RATE, slot, null).params) shouldBe
        figuresOf(look.pictureParams(level, slot.start))
      look.isInert shouldBe false
    }
  }

  @Test
  fun `a look is inert only when it starts and ends at nothing`() {
    Vhs(intensity = 0f).isInert shouldBe true
    Vhs(intensity = 0f, settle = TapeSettle(0f, OVER)).isInert shouldBe true
    Vhs(intensity = 0f, settle = TapeSettle(0.5f, OVER)).isInert shouldBe false
    Vhs(intensity = 0.5f, settle = TapeSettle(0f, OVER)).isInert shouldBe false
  }

  @Test
  fun `the builder makes the same looks as the constructors`() {
    val built =
      EffectsBuilder()
        .vhs {
          intensity = 0.6f
          tape = TapeSpeed.SP
          settle(from = 1f, over = OVER, easing = Easing.EaseOut)
        }.betamax { settle(0.2f, OVER) }
        .hi8 { settle = TapeSettle(0.9f, OVER) }
        .build()

    built shouldBe
      listOf(
        Vhs(0.6f, TapeSpeed.SP, settle = TapeSettle(1f, OVER, Easing.EaseOut)),
        Betamax(settle = TapeSettle(0.2f, OVER)),
        Hi8(settle = TapeSettle(0.9f, OVER)),
      )
  }

  @Test
  fun `a changed settle is a changed look`() {
    TapeSettle(0.5f, OVER) shouldBe TapeSettle(0.5f, OVER, Easing.EaseInOut)
    TapeSettle(0.5f, OVER) shouldNotBe TapeSettle(0.6f, OVER)
    TapeSettle(0.5f, OVER) shouldNotBe TapeSettle(0.5f, OVER + 1.seconds)
    TapeSettle(0.5f, OVER) shouldNotBe TapeSettle(0.5f, OVER, Easing.Linear)
    Vhs(settle = TapeSettle(0.5f, OVER)) shouldNotBe Vhs()
    Vhs(settle = TapeSettle(0.5f, OVER)) shouldBe Vhs(settle = TapeSettle(0.5f, OVER))
  }

  @Test
  fun `refuses a settle out of range`() {
    shouldThrow<IllegalArgumentException> { TapeSettle(-0.1f, OVER) }
    shouldThrow<IllegalArgumentException> { TapeSettle(1.1f, OVER) }
    shouldThrow<IllegalArgumentException> { TapeSettle(0.5f, Duration.ZERO) }
    shouldThrow<IllegalArgumentException> { TapeSettle(0.5f, (-1).seconds) }
    shouldThrow<IllegalArgumentException> { TapeSettle(0.5f, Duration.INFINITE) }
  }

  private fun rampOf(easing: Easing) =
    TapePictureRamp.of(Vhs(intensity = TO, settle = TapeSettle(FROM, OVER, easing)), slot).shouldNotBeNull()

  private fun figuresOf(params: TapePictureParams): Map<String, Double> =
    mapOf(
      "lumaSigma" to params.lumaSigma,
      "chromaSigma" to params.chromaSigma,
      "wideSigma" to params.wideSigma,
      "haloGain" to params.haloGain,
      "jitter" to params.jitter,
      "wave" to params.wave,
      "headShift" to params.headShift,
      "chromaShift" to params.chromaShift,
      "lumaNoise" to params.lumaNoise,
      "chromaNoise" to params.chromaNoise,
      "dropoutChance" to params.dropoutChance,
      "dropoutLength" to params.dropoutLength,
      "trackingChance" to params.trackingChance,
      "trackingShift" to params.trackingShift,
      "trackingNoise" to params.trackingNoise,
      "trackingSnow" to params.trackingSnow,
      "desaturation" to params.desaturation,
      "lift" to params.lift,
    )

  private companion object {
    const val FROM = 1f
    const val TO = 0.3f
    val OVER = 4.seconds

    // The figures that scale with the level, and the ones that scale with its square.
    val SQUARED = setOf("dropoutChance", "trackingChance")
    val FOLLOWING =
      setOf(
        "lumaSigma",
        "chromaSigma",
        "haloGain",
        "jitter",
        "wave",
        "headShift",
        "chromaShift",
        "lumaNoise",
        "chromaNoise",
        "dropoutChance",
        "trackingChance",
        "trackingShift",
        "trackingNoise",
        "trackingSnow",
        "desaturation",
        "lift",
      )
  }
}
