package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FrameInfo
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernels' own output for a [Film]'s fade and breath, with no encoder in the way, against the signal.
 *
 * The picture ramps across from a warm dark code to a cool bright one, so the tone is read on the middle of the code
 * range in every channel. Every test first checks that [FilmTone.toned] moves a mid code at the tick it reads, so a
 * seed that draws next to nothing there fails the test rather than passing it on nothing.
 */
@OptIn(ExperimentalForeignApi::class)
class FilmToneCoreImageHardwareTest {
  @Test
  fun `a mid fade lands on the signal`() {
    assertToneMatches(Film(grain = 0f, halation = 0f, fade = 0.5f), WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun `a breath between two knots lands on the signal`() {
    (FilmSignal.grainTickAt(BETWEEN) % FilmTone.KNOT_TICKS) shouldNotBe 0
    assertToneMatches(Film(grain = 0f, halation = 0f, breath = 0.6f, seed = OTHER_SEED), WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun `a breath on a knot lands on the signal`() {
    val film = Film(grain = 0f, halation = 0f, breath = 0.6f, seed = OTHER_SEED)

    (FilmSignal.grainTickAt(ON_KNOT) % FilmTone.KNOT_TICKS) shouldBe 0
    assertToneMatches(film, WIDTH, HEIGHT, TOLERANCE, ON_KNOT)
  }

  @Test
  fun `a fade and a breath under every other control land on the signal`() {
    val film =
      Film(0.5f, 0.5f, flicker = 0.5f, dust = 0.5f, gate = 0.5f, leak = 0.25f, breath = 0.6f, fade = 0.5f, seed = 68)

    assertToneMatches(film, WIDTH, HEIGHT, BLUR_CODES * FilmParams.of(film).halationGain / FULL + TOLERANCE)
  }

  @Test
  fun `a smaller frame draws the same tone at its own size`() {
    val film = Film(grain = 0f, halation = 0f, breath = 0.6f, fade = 0.5f, seed = OTHER_SEED)

    assertToneMatches(film, WIDTH * 3 / 4, HEIGHT * 3 / 4, TOLERANCE)
  }

  @Test
  fun `the same time draws the same breath after a seek`() {
    val film = Film(grain = 0f, halation = 0f, breath = 1f, seed = OTHER_SEED)
    val first = drawn(film, WIDTH, HEIGHT, BETWEEN)
    val elsewhere = drawn(film, WIDTH, HEIGHT, ON_KNOT)
    val again = drawn(film, WIDTH, HEIGHT, BETWEEN)

    withClue("the breath drifts between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun `a bar under the fade keeps its alpha and stays clear`() {
    val film = Film(grain = 0f, halation = 0f, fade = 1f)
    val canvas =
      filmImageOf(WIDTH, HEIGHT) { x, _ ->
        if (x in CANVAS_LEFT until CANVAS_RIGHT) pixelOf(x, WIDTH) else CLEAR_BLACK
      }
    val frame =
      filmRendered(
        filmStep(film).apply(canvas, FrameInfo(filmAttributes(WIDTH, HEIGHT), BETWEEN.seconds)),
        WIDTH,
        HEIGHT,
      )
    val bar = CANVAS_LEFT / 2 to HEIGHT / 2

    (0 until FILM_CHANNELS).forEach { channel ->
      withClue("channel $channel of the bar at $bar") {
        frame[(bar.second * WIDTH + bar.first) * FILM_CHANNELS + channel].toDouble() shouldBe
          (0.0 plusOrMinus TOLERANCE)
      }
    }
  }

  private fun assertToneMatches(
    film: Film,
    width: Int,
    height: Int,
    tolerance: Double,
    seconds: Double = BETWEEN,
  ) {
    val params = FilmParams.of(film)
    val tick = FilmSignal.grainTickAt(seconds)
    val glow = FilmSignal.halationGlow(width, height) { x, _ -> codesAt(x, width) }
    val frame = drawn(film, width, height, seconds)

    withClue("the tone has to move a mid code for this to tell") {
      val mid = codesAt(width / 2, width)
      val toned = FilmTone.toned(mid[0], mid[1], mid[2], tick, params)
      mid.indices.maxOf { abs(toned[it] - mid[it]) } shouldBeGreaterThan MOVED
    }
    val columns = listOf(width / 8, width / 2 - 2, width / 2 + 2, width - 2)
    val places = listOf(height / 8, height / 2, height - 2).flatMap { y -> columns.map { x -> x to y } }
    places.forEach { (x, y) ->
      val codes = codesAt(x, width)
      val wanted =
        FilmSignal.pixelAt(
          codes[0],
          codes[1],
          codes[2],
          glow.at(x, y),
          FilmSignal.grainCell(x, y, width, height),
          tick,
          params,
          (x + HALF) / width,
          (y + HALF) / height,
          width.toDouble() / height,
        )
      wanted.forEachIndexed { channel, value ->
        withClue("row $y column $x channel $channel of a ${width}x$height frame") {
          frame[(y * width + x) * FILM_CHANNELS + channel].toDouble() shouldBe (value plusOrMinus tolerance)
        }
      }
    }
  }

  // Sixteenths of the way across, so every code is one a float holds exactly.
  private fun codesAt(
    x: Int,
    width: Int,
  ): DoubleArray {
    val across = (x * STEPS / width) / STEPS.toDouble()
    return doubleArrayOf(0.25 + 0.5 * across, 0.5, 0.75 - 0.5 * across)
  }

  private fun pixelOf(
    x: Int,
    width: Int,
  ): FloatArray {
    val codes = codesAt(x, width)
    return floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
  }

  private fun drawn(
    film: Film,
    width: Int,
    height: Int,
    seconds: Double,
  ): FloatArray =
    filmRendered(
      filmStep(film).apply(
        filmImageOf(width, height) { x, _ -> pixelOf(x, width) },
        FrameInfo(filmAttributes(width, height), seconds.seconds),
      ),
      width,
      height,
    )

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 288
    const val OTHER_SEED = 68
    const val STEPS = 16
    const val HALF = 0.5
    const val FULL = 255.0
    const val ON_KNOT = 25.0
    const val BETWEEN = 25.25
    const val MOVED = 0.01
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384

    // What a float kernel's own arithmetic leaves of the tone.
    const val TOLERANCE = 1e-4

    // Core Image's Gaussian is Core Image's own limit, the one the film's kernel test pins.
    const val BLUR_CODES = 2.0
    val CLEAR_BLACK = floatArrayOf(0f, 0f, 0f, 0f)
  }
}
