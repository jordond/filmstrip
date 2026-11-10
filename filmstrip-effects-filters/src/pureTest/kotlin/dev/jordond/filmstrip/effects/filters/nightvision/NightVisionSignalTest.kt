package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * The numbers every night vision lowering reads, at the middle of their ranges.
 */
class NightVisionSignalTest {
  private val params = NightVisionParams.at(1.0)

  @Test
  fun `lift is the gain below the knee`() {
    val luma = NIGHT_VISION_KNEE / NIGHT_VISION_GAIN / 2

    NightVisionSignal.lift(luma, params) shouldBe (luma * NIGHT_VISION_GAIN plusOrMinus EPSILON)
  }

  @Test
  fun `lift rolls off past the knee without a step and stays under 1`() {
    val atKnee = NIGHT_VISION_KNEE / NIGHT_VISION_GAIN
    val past = (atKnee + 1.0) / 2

    NightVisionSignal.lift(atKnee + 1e-6, params) shouldBe (NIGHT_VISION_KNEE plusOrMinus 1e-5)
    NightVisionSignal.lift(past, params) shouldBeGreaterThan NightVisionSignal.lift(past - 0.05, params)
    NightVisionSignal.lift(past, params) shouldBeLessThan past * NIGHT_VISION_GAIN
    NightVisionSignal.lift(1.0, params) shouldBeLessThan 1.0
  }

  @Test
  fun `the ramp at a mid luma sits between the green and the top on its line`() {
    val luma = (1.0 + RAMP_MID) / 2 / NIGHT_VISION_GAIN
    val level = NightVisionSignal.lift(luma, params)
    val t = (level - RAMP_MID) / (1.0 - RAMP_MID)
    level shouldBeGreaterThan RAMP_MID

    val codes = NightVisionSignal.phosphor(luma, params)

    for (channel in 0..2) {
      val expected = RAMP_MID_COLOUR[channel] + (RAMP_TOP_COLOUR[channel] - RAMP_MID_COLOUR[channel]) * t
      codes[channel] shouldBe (expected plusOrMinus EPSILON)
    }
    codes[1] shouldBeGreaterThan codes[0]
    codes[1] shouldBeGreaterThan codes[2]
  }

  @Test
  fun `the ramp below its green scales the green down to black`() {
    val codes = NightVisionSignal.ramp(RAMP_MID / 2)

    codes.toList() shouldBe RAMP_MID_COLOUR.map { it / 2 }
    NightVisionSignal.ramp(0.0).toList() shouldBe listOf(0.0, 0.0, 0.0)
  }

  @Test
  fun `two pixels of equal luma and a different hue land the same`() {
    val reddish = doubleArrayOf(0.7, 0.3, 0.2)
    val luma = lumaOf(reddish[0], reddish[1], reddish[2])
    val grey = doubleArrayOf(luma, luma, luma)

    val fromRed = NightVisionSignal.pixelAt(reddish[0], reddish[1], reddish[2], 300, 200, WIDTH, HEIGHT, 9, params)
    val fromGrey = NightVisionSignal.pixelAt(grey[0], grey[1], grey[2], 300, 200, WIDTH, HEIGHT, 9, params)

    for (channel in 0..2) fromRed[channel] shouldBe (fromGrey[channel] plusOrMinus EPSILON)
  }

  @Test
  fun `the noise is film's grain on the look's own seed and heavier than film's`() {
    val cell = FilmSignal.grainCell(640, 360, WIDTH, HEIGHT)
    val seeded = NightVisionParams.at(1.0, seed = 77)

    NightVisionSignal.noiseAt(77, 12, cell, seeded) shouldBe NIGHT_VISION_NOISE * FilmSignal.grainAt(cell, 12, 77)
    NightVisionSignal.noiseAt(77, 12, cell, seeded) shouldNotBe NightVisionSignal.noiseAt(78, 12, cell, seeded)
    params.noise shouldBeGreaterThan FilmParams.at(grain = 1.0, halation = 0.0).grainStrength
  }

  @Test
  fun `the vignette reads the pixel centre as frame fractions`() {
    val expected =
      vignetteGain(
        (300 + 0.5) / WIDTH,
        (100 + 0.5) / HEIGHT,
        WIDTH.toDouble() / HEIGHT,
        NIGHT_VISION_VIGNETTE_STRENGTH,
        NIGHT_VISION_VIGNETTE_RADIUS,
        NIGHT_VISION_VIGNETTE_SOFTNESS,
      )

    NightVisionSignal.vignetteAt(300, 100, WIDTH, HEIGHT) shouldBe expected
    NightVisionSignal.vignetteAt(0, 0, WIDTH, HEIGHT) shouldBeLessThan expected
    NightVisionSignal.vignetteAt(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT) shouldBe (1.0 plusOrMinus EPSILON)
  }

  @Test
  fun `the same frame fraction reads the same vignette at a preview size`() {
    NightVisionSignal.vignetteAt(320, 180, WIDTH, HEIGHT) shouldBe
      (NightVisionSignal.vignetteAt(640, 360, WIDTH * 2, HEIGHT * 2) plusOrMinus 0.01)
  }

  @Test
  fun `a pixel at half intensity sits half way between the frame and the full look`() {
    val half = NightVisionParams.at(0.5)
    val red = 0.5
    val green = 0.4
    val blue = 0.3
    val x = 400
    val y = 250
    val tick = 31
    val cell = FilmSignal.grainCell(x, y, WIDTH, HEIGHT)
    val noisy = lumaOf(red, green, blue) + NightVisionSignal.noiseAt(half.seed, tick, cell, half)
    val look = NightVisionSignal.phosphor(noisy, half)
    val gain = NightVisionSignal.vignetteAt(x, y, WIDTH, HEIGHT)
    val original = doubleArrayOf(red, green, blue)

    val codes = NightVisionSignal.pixelAt(red, green, blue, x, y, WIDTH, HEIGHT, tick, half)

    for (channel in 0..2) {
      codes[channel] shouldBe ((original[channel] + look[channel] * gain) / 2 plusOrMinus EPSILON)
    }
  }

  @Test
  fun `a pixel at intensity 0 keeps its codes`() {
    val codes = NightVisionSignal.pixelAt(0.5, 0.4, 0.3, 10, 10, WIDTH, HEIGHT, 3, NightVisionParams.at(0.0))

    codes.toList() shouldBe listOf(0.5, 0.4, 0.3)
  }

  @Test
  fun `the grain changes with the tick a seek lands on`() {
    val tick = FilmSignal.grainTickAt(2.5)
    val at = NightVisionSignal.pixelAt(0.5, 0.5, 0.5, 700, 300, WIDTH, HEIGHT, tick, params)
    val again = NightVisionSignal.pixelAt(0.5, 0.5, 0.5, 700, 300, WIDTH, HEIGHT, tick, params)
    val next = NightVisionSignal.pixelAt(0.5, 0.5, 0.5, 700, 300, WIDTH, HEIGHT, tick + 1, params)

    again.toList() shouldBe at.toList()
    next.toList() shouldNotBe at.toList()
  }
}

private const val WIDTH = 1280
private const val HEIGHT = 720
private const val EPSILON = 1e-9
