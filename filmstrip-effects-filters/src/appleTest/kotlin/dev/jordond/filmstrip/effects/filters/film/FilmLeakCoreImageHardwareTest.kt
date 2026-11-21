package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FrameInfo
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernels' own output for a [Film]'s light leaks, with no encoder in the way, against the signal.
 *
 * The picture is a gray edge down the middle, so the screen is read on a dark code and a bright one. Every test reads
 * the place [FilmLeak.leakAt] is brightest and first checks the leak is lit there, so a seed that draws its spots
 * somewhere else fails the test rather than passing it on nothing.
 */
@OptIn(ExperimentalForeignApi::class)
class FilmLeakCoreImageHardwareTest {
  @Test
  fun `a leak at a mid strength lands on the signal`() {
    assertLeakMatches(Film(grain = 0f, halation = 0f, leak = 0.5f), WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun `a light leak under grain and halation lands on the signal`() {
    val film = Film(grain = 0.5f, halation = 0.5f, leak = 0.25f, seed = OTHER_SEED)
    assertLeakMatches(film, WIDTH, HEIGHT, BLUR_CODES * FilmParams.of(film).halationGain / FULL + TOLERANCE)
  }

  @Test
  fun `a full leak under the flicker and the gate lands on the signal`() {
    assertLeakMatches(Film(grain = 0f, halation = 0f, flicker = 0.5f, gate = 0.5f, leak = 1f), WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun `a smaller frame draws the same leak at its own size`() {
    assertLeakMatches(Film(grain = 0f, halation = 0f, leak = 0.5f), WIDTH * 3 / 4, HEIGHT * 3 / 4, TOLERANCE)
  }

  @Test
  fun `a place past every spot keeps the picture`() {
    val film = Film(grain = 0f, halation = 0f, leak = 0.5f, seed = darkSeed())
    val frame = drawn(film, WIDTH, HEIGHT)
    val dark = unlit(film).first()

    withClue("the leak has to be lit somewhere for this to tell") {
      lightAt(brightest(film, WIDTH, HEIGHT), film, WIDTH, HEIGHT) shouldBeGreaterThan LIT
    }
    codesAt(dark.first, WIDTH).forEachIndexed { channel, code ->
      frame[(dark.second * WIDTH + dark.first) * FILM_CHANNELS + channel].toDouble() shouldBe
        (code plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `the same time draws the same leak after a seek`() {
    val film = Film(grain = 0f, halation = 0f, leak = 0.5f)
    val first = drawn(film, WIDTH, HEIGHT)
    val elsewhere = drawn(film, WIDTH, HEIGHT, seconds = SECONDS + 1.0)
    val again = drawn(film, WIDTH, HEIGHT)

    withClue("the leak moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun `a bar under the leak keeps its alpha and stays clear`() {
    val film = Film(grain = 0f, halation = 0f, leak = 1f)
    val canvas =
      filmImageOf(WIDTH, HEIGHT) { x, _ ->
        if (x in
          CANVAS_LEFT until CANVAS_RIGHT
        ) {
          floatArrayOf(LIGHT.toFloat(), LIGHT.toFloat(), LIGHT.toFloat(), 1f)
        } else {
          CLEAR_BLACK
        }
      }
    val frame =
      filmRendered(
        filmStep(film).apply(canvas, FrameInfo(filmAttributes(WIDTH, HEIGHT), SECONDS.seconds)),
        WIDTH,
        HEIGHT,
      )
    val bar =
      placesOf(WIDTH, HEIGHT)
        .filter { it.first !in CANVAS_LEFT - PLACE_STEP until CANVAS_RIGHT + PLACE_STEP }
        .maxBy { lightAt(it, film, WIDTH, HEIGHT) }

    withClue("the leak has to be lit over the bar for this to tell") {
      lightAt(bar, film, WIDTH, HEIGHT) shouldBeGreaterThan LIT
    }
    (0 until FILM_CHANNELS).forEach { channel ->
      withClue("channel $channel of the bar at $bar") {
        frame[(bar.second * WIDTH + bar.first) * FILM_CHANNELS + channel].toDouble() shouldBe
          (0.0 plusOrMinus TOLERANCE)
      }
    }
  }

  private fun assertLeakMatches(
    film: Film,
    width: Int,
    height: Int,
    tolerance: Double,
  ) {
    val params = FilmParams.of(film)
    val tick = FilmSignal.grainTickAt(SECONDS)
    val glow = FilmSignal.halationGlow(width, height) { x, _ -> codesAt(x, width) }
    val frame = drawn(film, width, height)
    val lit = brightest(film, width, height)

    withClue("the leak has to be lit for this to tell") { lightAt(lit, film, width, height) shouldBeGreaterThan LIT }
    val columns = listOf(width / 8, width / 2 - 2, width / 2 + 2, width - 2)
    val places = listOf(height / 8, height / 2, height - 2).flatMap { y -> columns.map { x -> x to y } } + lit
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

  private fun lightAt(
    place: Pair<Int, Int>,
    film: Film,
    width: Int,
    height: Int,
  ): Double =
    FilmLeak
      .leakAt(
        (place.first + HALF) / width,
        (place.second + HALF) / height,
        width.toDouble() / height,
        FilmSignal.grainTickAt(SECONDS),
        film.seed,
        film.leak.toDouble(),
      ).max()

  // Every eighth pixel each way, clear of the frame's edge.
  private fun placesOf(
    width: Int,
    height: Int,
  ): List<Pair<Int, Int>> =
    (PLACE_STEP until height - PLACE_STEP step PLACE_STEP).flatMap { y ->
      (PLACE_STEP until width - PLACE_STEP step PLACE_STEP).map { x -> x to y }
    }

  private fun brightest(
    film: Film,
    width: Int,
    height: Int,
  ): Pair<Int, Int> = placesOf(width, height).maxBy { lightAt(it, film, width, height) }

  private fun unlit(film: Film): List<Pair<Int, Int>> =
    placesOf(WIDTH, HEIGHT).filter { lightAt(it, film, WIDTH, HEIGHT) == 0.0 }

  // The first seed from 1 whose leak leaves some of the frame dark at the read's tick.
  private fun darkSeed(): Int =
    (1..SEED_SEARCH).first { seed -> unlit(Film(grain = 0f, halation = 0f, leak = 0.5f, seed = seed)).isNotEmpty() }

  private fun codesAt(
    x: Int,
    width: Int,
  ): DoubleArray = DoubleArray(3) { if (x < width / 2) LIGHT else DARK }

  private fun drawn(
    film: Film,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
  ): FloatArray {
    val image =
      filmImageOf(width, height) { x, _ ->
        val codes = codesAt(x, width)
        floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
      }
    return filmRendered(
      filmStep(film).apply(image, FrameInfo(filmAttributes(width, height), seconds.seconds)),
      width,
      height,
    )
  }

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 288
    const val OTHER_SEED = 7
    const val SEED_SEARCH = 5_000
    const val PLACE_STEP = 8
    const val HALF = 0.5
    const val FULL = 255.0
    const val SECONDS = 25.0
    const val LIGHT = 0.875
    const val DARK = 0.375
    const val LIT = 0.1
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384

    // What a float kernel's own arithmetic leaves of the leak's sine and falloff.
    const val TOLERANCE = 1e-4

    // Core Image's Gaussian is Core Image's own limit, the one the film's kernel test pins.
    const val BLUR_CODES = 2.0
    val CLEAR_BLACK = floatArrayOf(0f, 0f, 0f, 0f)
  }
}
