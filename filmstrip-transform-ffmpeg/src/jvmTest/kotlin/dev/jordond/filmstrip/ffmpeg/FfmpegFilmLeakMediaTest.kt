package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmLeak
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertFilmLanded
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.filmReadTick
import dev.jordond.filmstrip.ffmpeg.support.planeCodesOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Film]'s light leaks against the signal.
 *
 * The picture is a gray edge down the middle, so the screen is read on a dark code and a bright one. Every test reads
 * the place [FilmLeak.leakAt] is brightest and first checks the leak is lit there, so a seed that draws its spots
 * somewhere else fails the test rather than passing it on nothing.
 */
class FfmpegFilmLeakMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a leak at a mid strength lands on the signal`() =
    runBlocking<Unit> { assertLeakReadsSignal(Film(grain = 0f, halation = 0f, leak = 0.5f)) }

  @Test
  fun `a light leak under grain and halation lands on the signal`() =
    runBlocking<Unit> { assertLeakReadsSignal(Film(grain = 0.5f, halation = 0.5f, leak = 0.25f, seed = OTHER_SEED)) }

  @Test
  fun `a full leak under the flicker and the gate lands on the signal`() =
    runBlocking<Unit> {
      assertLeakReadsSignal(Film(grain = 0f, halation = 0f, flicker = 0.5f, gate = 0.5f, leak = 1f))
    }

  @Test
  fun `a smaller preview draws the same leak at its own size`() =
    runBlocking<Unit> {
      assertLeakReadsSignal(Film(grain = 0f, halation = 0f, leak = 0.5f), ExportSpec(heightPx = HEIGHT * 3 / 4))
    }

  @Test
  fun `a place past every spot keeps the picture`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0f, leak = 0.5f, seed = darkSeed())
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(film)), FILM_READ_SLOT)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)

      val dark = unlit(film).first()
      withClue("the leak has to be lit somewhere for this to tell") {
        lightAt(brightest(film, WIDTH, HEIGHT), film, WIDTH, HEIGHT) shouldBeGreaterThan LIT
      }
      frame.luma(dark.first, dark.second) shouldBe plain.luma(dark.first, dark.second)
      assertFilmLanded(frame, plain, film, listOf(dark, brightest(film, WIDTH, HEIGHT)))
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0f, leak = 0.5f)
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::edgeAt)), listOf(film))
      val at = (FILM_READ_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, FILM_READ_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip leak after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0f, leak = 1f)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(crop, film))
      val frame = draw.frameAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), Duration.ZERO, FILM_READ_SLOT)

      // A wide picture on a square canvas leaves a bar above and below, and the canvas is the clip's height a side.
      val bar =
        (PLACE_STEP until HEIGHT - PLACE_STEP step PLACE_STEP)
          .flatMap { x -> listOf(x to BAR_ROW, x to HEIGHT - 1 - BAR_ROW) }
          .maxBy { lightAt(it, film, HEIGHT, HEIGHT) }
      withClue("the leak has to be lit over the bar for this to tell") {
        lightAt(bar, film, HEIGHT, HEIGHT) shouldBeGreaterThan LIT
      }
      (0 until 3).forEach { channel ->
        withClue("channel $channel") {
          frame.channelAt(HEIGHT, bar.first, bar.second, channel).toDouble() shouldBe
            (SLATE_CODES[channel] plusOrMinus BAR_CODES)
        }
      }
    }

  @Test
  fun `the widened map stays within its limit of the light read at every pixel`() =
    runBlocking<Unit> { assertMapWithinLimit(WIDTH, HEIGHT) }

  @Test
  fun `a small frame's map stays within the same limit`() =
    runBlocking<Unit> {
      assertMapWithinLimit(SMALL_WIDTH, SMALL_HEIGHT)
    }

  @Test
  fun `a frame narrower than its own map still draws the leak`() =
    runBlocking<Unit> { assertMapWithinLimit(TINY, TINY) }

  private suspend fun assertMapWithinLimit(
    width: Int,
    height: Int,
  ) {
    val film = Film(grain = 0f, halation = 0f, leak = 1f)
    val clip = draw.clipOf(width, height, { doubleArrayOf(0.0, 0.0, 0.0) })
    val frame = draw.planesAt(draw.composition(listOf(clip), listOf(film)), FILM_READ_SLOT)

    var worst = 0.0
    var lit = 0.0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val light = leakAt(x to y, film, width, height)
        lit = max(lit, light.max())
        worst = max(worst, abs(frame.luma(x, y) - planeCodesOf(FilmLeak.leaked(0.0, 0.0, 0.0, light))[0]))
      }
    }
    withClue("the leak has to be lit for this to tell") { lit shouldBeGreaterThan LIT }
    worst shouldBeLessThan WIDENED_LUMA_CODES
  }

  private suspend fun assertLeakReadsSignal(
    film: Film,
    spec: ExportSpec = ExportSpec(),
  ) {
    val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
    val frame = draw.planesAt(draw.composition(listOf(clip), listOf(film)), FILM_READ_SLOT, spec)
    val plain = draw.planesAt(draw.composition(listOf(clip)), 0, spec)
    val lit = brightest(film, frame.width, frame.height)

    withClue("the leak has to be lit for this to tell") {
      lightAt(lit, film, frame.width, frame.height) shouldBeGreaterThan LIT
    }
    assertFilmLanded(frame, plain, film, listOf(lit))
  }

  private fun leakAt(
    place: Pair<Int, Int>,
    film: Film,
    width: Int,
    height: Int,
  ): DoubleArray =
    FilmLeak.leakAt(
      (place.first + 0.5) / width,
      (place.second + 0.5) / height,
      width.toDouble() / height,
      filmReadTick(),
      film.seed,
      film.leak.toDouble(),
    )

  private fun lightAt(
    place: Pair<Int, Int>,
    film: Film,
    width: Int,
    height: Int,
  ): Double = leakAt(place, film, width, height).max()

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

  private fun edgeAt(x: Double): DoubleArray = DoubleArray(3) { if (x < 0.5) LIGHT else DARK }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val SMALL_WIDTH = 160
    const val SMALL_HEIGHT = 90
    const val TINY = 2
    const val OTHER_SEED = 7
    const val SEED_SEARCH = 5_000
    const val PLACE_STEP = 8
    const val LIGHT = 0.875
    const val DARK = 0.375
    const val LIT = 0.1
    const val BAR_ROW = 16
    const val BAR_CODES = 3.0
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())

    // How far the map, drawn at an eighth of the frame a side and widened between its samples, lands from the light
    // read at each pixel, in luma codes over black. The worst pixel measured 1.05 on ffmpeg 8.0.1, most of it the two
    // roundings of the trip through gbrap.
    const val WIDENED_LUMA_CODES = 1.5
  }
}
