package dev.jordond.filmstrip.effects.filters.film

import android.opengl.GLES30
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads the chain with the fade and the breath on against [FilmSignal.pixelAt].
 *
 * The input ramps across and down under a bright patch, in codes half floats hold exactly, so the tone is read on
 * dark codes and bright ones. Each test first checks that the tone moves the frame where it reads, so a seed that
 * draws next to nothing at its tick fails the test rather than passing it on nothing.
 */
class FilmToneShaderProgramMediaTest {
  private val gl = FilmGlContext()

  @BeforeTest
  fun makeContext() = gl.open()

  @AfterTest
  fun dropContext() = gl.close()

  @Test
  fun aMidFadeLandsOnTheSignal() {
    val film = Film(0f, 0f, fade = 0.5f)

    assertToneMatches(draw(film, WIDTH, HEIGHT, BETWEEN), film, BETWEEN, WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun aBreathBetweenTwoKnotsLandsOnTheSignal() {
    val film = Film(0f, 0f, breath = 0.6f, seed = OTHER_SEED)

    (BETWEEN % FilmTone.KNOT_TICKS) shouldNotBe 0
    assertToneMatches(draw(film, WIDTH, HEIGHT, BETWEEN), film, BETWEEN, WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun aBreathOnAKnotLandsOnTheSignal() {
    val film = Film(0f, 0f, breath = 0.6f, seed = OTHER_SEED)

    (ON_KNOT % FilmTone.KNOT_TICKS) shouldBe 0
    assertToneMatches(draw(film, WIDTH, HEIGHT, ON_KNOT), film, ON_KNOT, WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun aToneUnderEveryOtherControlLandsOnTheSignal() {
    val film =
      Film(0.5f, 0.5f, flicker = 0.5f, dust = 0.5f, gate = 0.5f, leak = 0.25f, breath = 0.6f, fade = 0.5f, seed = 7)

    assertToneMatches(draw(film, WIDTH, HEIGHT, BETWEEN), film, BETWEEN, WIDTH, HEIGHT, CHAIN_TOLERANCE)
  }

  @Test
  fun aSmallerPreviewDrawsTheSameToneAtItsOwnSize() {
    val film = Film(0f, 0f, breath = 0.6f, fade = 0.5f, seed = OTHER_SEED)
    val width = WIDTH * 3 / 4
    val height = HEIGHT * 3 / 4

    assertToneMatches(draw(film, width, height, BETWEEN), film, BETWEEN, width, height, TOLERANCE)
  }

  @Test
  fun theSameTimeDrawsTheSameBreathAfterASeek() {
    val film = Film(0f, 0f, breath = 1f, seed = OTHER_SEED)
    val first = draw(film, WIDTH, HEIGHT, BETWEEN)
    val elsewhere = draw(film, WIDTH, HEIGHT, ON_KNOT)
    val again = draw(film, WIDTH, HEIGHT, BETWEEN)

    withClue("the breath drifts between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun aBarUnderTheFadeStaysClear() {
    val film = Film(0f, 0f, fade = 1f)
    val source = { x: Int, y: Int ->
      if (y < BAR_ROWS || y >= HEIGHT - BAR_ROWS) floatArrayOf(0f, 0f, 0f, 0f) else codesAt(x, y, WIDTH, HEIGHT)
    }
    val frame = draw(film, WIDTH, HEIGHT, BETWEEN, source)

    withClue("the bar stays clear") {
      frame[indexOf(WIDTH / 2 to BAR_ROWS / 2, WIDTH, HEIGHT) + 3].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    val inside = WIDTH * 3 / 4 to HEIGHT / 2
    assertPixel(frame, inside, WIDTH, HEIGHT, wantedAt(inside, film, BETWEEN, WIDTH, HEIGHT, null), TOLERANCE)
  }

  // Reads a grid over the patch and the ramp, after checking the tone moves the frame somewhere on it.
  private fun assertToneMatches(
    frame: FloatArray,
    film: Film,
    tick: Int,
    width: Int,
    height: Int,
    tolerance: Double,
  ) {
    val glow =
      if (FilmParams.of(film).halationGain > 0.0) {
        FilmSignal.halationGlow(width, height) { x, y ->
          val codes = codesAt(x, y, width, height)
          doubleArrayOf(codes[0].toDouble(), codes[1].toDouble(), codes[2].toDouble())
        }
      } else {
        null
      }
    val columns = listOf(0, width / 8, width / 2 - 1, width / 2 + 1, width * 3 / 4, width - 1)
    val rows = listOf(0, height / 4, height / 2 - 1, height / 2 + 1, height - 1)
    val points = rows.flatMap { y -> columns.map { x -> x to y } }
    val tone = FilmParams.at(0.0, 0.0, breath = film.breath.toDouble(), fade = film.fade.toDouble(), seed = film.seed)
    withClue("the tone has to move the frame for this to tell") {
      points.maxOf { place ->
        val codes = codesAt(place.first, place.second, width, height)
        val toned = FilmTone.toned(codes[0].toDouble(), codes[1].toDouble(), codes[2].toDouble(), tick, tone)
        (0 until RGB).maxOf { abs(toned[it] - codes[it]) }
      } shouldBeGreaterThan MOVED
    }
    points.forEach { place ->
      assertPixel(
        frame,
        place,
        width,
        height,
        wantedAt(place, film, tick, width, height, glow?.at(place.first, place.second)),
        tolerance,
      )
    }
  }

  private fun wantedAt(
    place: Pair<Int, Int>,
    film: Film,
    tick: Int,
    width: Int,
    height: Int,
    blurred: DoubleArray?,
  ): DoubleArray {
    val (x, y) = place
    val codes = codesAt(x, y, width, height)
    return FilmSignal.pixelAt(
      codes[0].toDouble(),
      codes[1].toDouble(),
      codes[2].toDouble(),
      blurred ?: DoubleArray(RGB),
      FilmSignal.grainCell(x, y, width, height),
      tick,
      FilmParams.of(film),
      (x + 0.5) / width,
      (y + 0.5) / height,
      width.toDouble() / height,
    ) + codes[3].toDouble()
  }

  private fun assertPixel(
    frame: FloatArray,
    place: Pair<Int, Int>,
    width: Int,
    height: Int,
    wanted: DoubleArray,
    tolerance: Double,
  ) {
    val at = indexOf(place, width, height)
    wanted.forEachIndexed { channel, value ->
      withClue("row ${place.second} column ${place.first} channel $channel of a ${width}x$height frame") {
        frame[at + channel].toDouble() shouldBe (value plusOrMinus tolerance)
      }
    }
  }

  // The frame reads bottom up, the way GL hands it back.
  private fun indexOf(
    place: Pair<Int, Int>,
    width: Int,
    height: Int,
  ): Int = ((height - 1 - place.second) * width + place.first) * CHANNELS

  // A bright patch in the upper left over a ramp across and down, every code a multiple of 1/1024 from a quarter up so
  // half floats hold it exactly.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): FloatArray {
    if (x < width / 2 && y < height / 2) return floatArrayOf(PATCH[0], PATCH[1], PATCH[2], 1f)
    val red = (QUARTER + floor(x * RAMP / width)) / STEPS
    val green = (QUARTER + floor(y * RAMP / height)) / STEPS
    return floatArrayOf(red.toFloat(), green.toFloat(), BLUE, 1f)
  }

  private fun draw(
    film: Film,
    width: Int,
    height: Int,
    tick: Int,
    source: (Int, Int) -> FloatArray = { x, y -> codesAt(x, y, width, height) },
  ): FloatArray = drawFilm(film, width, height, tick / GRAIN_TICK_RATE + INTO_TICK, GLES30.GL_RGBA16F, source)

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val RGB = 3
    const val OTHER_SEED = 68
    const val ON_KNOT = 50 * FilmTone.KNOT_TICKS
    const val BETWEEN = ON_KNOT + FilmTone.KNOT_TICKS / 2
    const val BAR_ROWS = 60

    // A time inside the tick, clear of its opening.
    const val INTO_TICK = 0.01
    const val QUARTER = 256.0
    const val RAMP = 400.0
    const val STEPS = 1024.0
    const val BLUE = 0.25f
    const val MOVED = 0.01
    const val TOLERANCE = 1e-4

    // The halation and the last step each round to half float, 2^-11 from a half to one.
    const val CHAIN_TOLERANCE = 1e-3
    val PATCH = floatArrayOf(0.875f, 0.8125f, 0.75f)
  }
}
