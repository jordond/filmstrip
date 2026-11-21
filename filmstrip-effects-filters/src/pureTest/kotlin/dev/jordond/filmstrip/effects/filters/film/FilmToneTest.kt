package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.min
import kotlin.test.Test

/**
 * The fade and the breath [FilmTone] gives a frame, and where [FilmSignal.pixelAt] lays them.
 */
class FilmToneTest {
  @Test
  fun `a fade of zero leaves the codes as they were`() {
    FilmTone.faded(0.3, 0.4, 0.5, 0.0).toList() shouldBe listOf(0.3, 0.4, 0.5)
  }

  @Test
  fun `a mid fade lands each mid code on its own scale and lift`() {
    val faded = FilmTone.faded(CODE, CODE, CODE, 0.5)

    for (channel in 0..2) {
      val expected = CODE * (1.0 - 0.5 * FADE_SPAN[channel]) + 0.5 * FADE_LIFT[channel]
      faded[channel] shouldBe (expected plusOrMinus TOLERANCE)
      faded[channel] shouldBe
        (CODE * FilmTone.fadeScale(channel, 0.5) + FilmTone.fadeLift(channel, 0.5) plusOrMinus TOLERANCE)
    }
    faded[0] shouldNotBe faded[1]
    faded[1] shouldNotBe faded[2]
  }

  @Test
  fun `a fade lifts black to its lift and holds white at or under one`() {
    for (fade in listOf(0.25, 0.5, 1.0)) {
      val black = FilmTone.faded(0.0, 0.0, 0.0, fade)
      val white = FilmTone.faded(1.0, 1.0, 1.0, fade)
      for (channel in 0..2) {
        black[channel] shouldBe (fade * FADE_LIFT[channel] plusOrMinus TOLERANCE)
        black[channel] shouldBeGreaterThan 0.0
        white[channel] shouldBeLessThanOrEqual 1.0
        white[channel] shouldBeGreaterThan black[channel]
      }
    }
  }

  @Test
  fun `a breath of zero is a contrast and three gains of one`() {
    FilmTone.breathAt(SEED, BETWEEN, 0.0).toList() shouldBe listOf(1.0, 1.0, 1.0, 1.0)
  }

  @Test
  fun `a frame on a knot breathes by that knot's draws`() {
    val breath = FilmTone.breathAt(SEED, KNOT * BREATH_KNOT_TICKS, 0.5)

    val warmth = 0.5 * BREATH_WARMTH * draw(PASS_BREATH_WARMTH, KNOT)
    breath[0] shouldBe (1.0 + 0.5 * BREATH_CONTRAST * draw(PASS_BREATH_CONTRAST, KNOT) plusOrMinus TOLERANCE)
    breath[1] shouldBe (1.0 + warmth plusOrMinus TOLERANCE)
    breath[2] shouldBe (1.0 + 0.5 * BREATH_TINT * draw(PASS_BREATH_TINT, KNOT) plusOrMinus TOLERANCE)
    breath[3] shouldBe (1.0 - warmth plusOrMinus TOLERANCE)
  }

  @Test
  fun `a frame between two knots eases from one to the next`() {
    val f = (BETWEEN - KNOT * BREATH_KNOT_TICKS).toDouble() / BREATH_KNOT_TICKS
    val ease = f * f * (3.0 - 2.0 * f)
    val from = draw(PASS_BREATH_CONTRAST, KNOT)
    val to = draw(PASS_BREATH_CONTRAST, KNOT + 1)

    val breath = FilmTone.breathAt(SEED, BETWEEN, 0.6)

    breath[0] shouldBe (1.0 + 0.6 * BREATH_CONTRAST * (from + (to - from) * ease) plusOrMinus TOLERANCE)
    breath[0] shouldNotBe FilmTone.breathAt(SEED, KNOT * BREATH_KNOT_TICKS, 0.6)[0]
    breath[0] shouldNotBe FilmTone.breathAt(SEED, (KNOT + 1) * BREATH_KNOT_TICKS, 0.6)[0]
  }

  @Test
  fun `equal seeds breathe alike and two seeds apart`() {
    FilmTone.breathAt(SEED, BETWEEN, 0.5).toList() shouldBe FilmTone.breathAt(SEED, BETWEEN, 0.5).toList()
    FilmTone.breathAt(SEED, BETWEEN, 0.5).toList() shouldNotBe FilmTone.breathAt(SEED + 1, BETWEEN, 0.5).toList()
  }

  @Test
  fun `the breath turns contrast around mid grey and then takes each gain`() {
    val breath = doubleArrayOf(1.1, 1.04, 0.98, 0.96)

    val breathed = FilmTone.breathed(0.3, 0.5, 0.8, breath)

    breathed[0] shouldBe (((0.3 - 0.5) * 1.1 + 0.5) * 1.04 plusOrMinus TOLERANCE)
    breathed[1] shouldBe (0.5 * 0.98 plusOrMinus TOLERANCE)
    breathed[2] shouldBe (((0.8 - 0.5) * 1.1 + 0.5) * 0.96 plusOrMinus TOLERANCE)
  }

  @Test
  fun `toned fades first and breathes on the faded print`() {
    val params = FilmParams.at(0.0, 0.0, breath = 0.6, fade = 0.5, seed = SEED)

    val toned = FilmTone.toned(0.3, 0.4, 0.5, BETWEEN, params)

    val print = FilmTone.faded(0.3, 0.4, 0.5, 0.5)
    val expected = FilmTone.breathed(print[0], print[1], print[2], FilmTone.breathAt(SEED, BETWEEN, 0.6))
    toned.toList() shouldBe expected.toList()
  }

  @Test
  fun `pixelAt with only a fade lands on the faded codes`() {
    val params = FilmParams.at(0.0, 0.0, fade = 0.5, seed = SEED)

    val pixel = FilmSignal.pixelAt(0.3, 0.4, 0.5, NO_GLOW, CELL, BETWEEN, params)

    pixel.toList() shouldBe FilmTone.faded(0.3, 0.4, 0.5, 0.5).toList()
    pixel.toList() shouldNotBe listOf(0.3, 0.4, 0.5)
  }

  @Test
  fun `pixelAt takes the flicker and the grain after the tone and clamps between them`() {
    val params = FilmParams.at(1.0, 0.0, flicker = 0.5, breath = 0.6, fade = 0.5, seed = SEED)

    val pixel = FilmSignal.pixelAt(0.3, 0.4, 0.97, NO_GLOW, CELL, BETWEEN, params)

    val toned = FilmTone.toned(0.3, 0.4, 0.97, BETWEEN, params)
    val gain = FilmSignal.flickerAt(SEED, BETWEEN, 0.5)
    val lit = toned.map { (it * gain).coerceIn(0.0, 1.0) }
    pixel.toList() shouldBe FilmSignal.grained(lit[0], lit[1], lit[2], CELL, BETWEEN, params).toList()
    pixel.toList() shouldNotBe lit
  }

  @Test
  fun `pixelAt with no fade and no breath keeps the flicker's own clamp`() {
    val params = FilmParams.at(0.0, 0.0, flicker = 1.0, seed = SEED)
    val gain = FilmSignal.flickerAt(SEED, BETWEEN, 1.0)

    val pixel = FilmSignal.pixelAt(0.3, 0.4, 0.99, NO_GLOW, CELL, BETWEEN, params)

    pixel.toList() shouldBe listOf(min(1.0, 0.3 * gain), min(1.0, 0.4 * gain), min(1.0, 0.99 * gain))
  }

  @Test
  fun `a fade or a breath alone is not inert and the small gauge preset carries both`() {
    Film(0f, 0f, fade = 0.5f).isInert shouldBe false
    Film(0f, 0f, breath = 0.5f).isInert shouldBe false
    (Film.Super8.fade > 0f) shouldBe true
    (Film.Super8.breath > 0f) shouldBe true
    FilmParams.of(Film(breath = 0.25f, fade = 0.5f)).breath shouldBe 0.25
    FilmParams.of(Film(breath = 0.25f, fade = 0.5f)).fade shouldBe 0.5
  }

  @Test
  fun `refuses a fade or a breath out of range`() {
    shouldThrow<IllegalArgumentException> { Film(fade = 1.5f) }
    shouldThrow<IllegalArgumentException> { Film(breath = -0.5f) }
  }

  private fun draw(
    pass: Int,
    knot: Int,
  ): Double = FilterNoise.snorm(FilterNoise.hash24(SEED, pass, knot, 0))

  private companion object {
    const val SEED = 77
    const val CODE = 0.4
    const val KNOT = 5
    const val BETWEEN = KNOT * BREATH_KNOT_TICKS + 4
    const val CELL = 4242
    const val TOLERANCE = 1e-12
    val NO_GLOW = doubleArrayOf(0.0, 0.0, 0.0)
  }
}
