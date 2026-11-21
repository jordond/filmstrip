package dev.jordond.filmstrip.effects.filters.film

import android.opengl.GLES30
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads the chain with the light leaks on against [FilmSignal.pixelAt].
 *
 * The input ramps across and down under a bright patch, in codes half floats hold exactly, so the screen is read on
 * dark codes and bright ones. Every place a test reads comes from [FilmLeak.leakAt], and each test first checks that
 * the leak is lit or dark there, so a seed that draws its spots somewhere else fails the test rather than passing it
 * on nothing. Each tick is one where both spots are part way through their run.
 */
class FilmLeakShaderProgramMediaTest {
  private val gl = FilmGlContext()

  @BeforeTest
  fun makeContext() = gl.open()

  @AfterTest
  fun dropContext() = gl.close()

  @Test
  fun aLeakAtAMidStrengthLandsOnTheSignal() {
    val film = Film(0f, 0f, leak = 0.5f)
    val tick = midRunTick(film, WIDTH, HEIGHT)
    val frame = draw(film, WIDTH, HEIGHT, tick)

    val lit = brightest(film, tick, WIDTH, HEIGHT)
    val dark = unlit(film, tick, WIDTH, HEIGHT).shouldNotBeNull()
    withClue("the leak lifts the frame where it is brightest") {
      val under = codesAt(lit.first, lit.second, WIDTH, HEIGHT)[0].toDouble()
      (frame[indexOf(lit, WIDTH, HEIGHT)] - under) shouldBeGreaterThan LIFT
    }
    withClue("a pixel past every spot's radius keeps the picture") {
      val under = codesAt(dark.first, dark.second, WIDTH, HEIGHT)
      assertPixel(frame, dark, WIDTH, HEIGHT, DoubleArray(CHANNELS) { under[it].toDouble() }, TOLERANCE)
    }
    assertLeakMatches(frame, film, tick, WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun aLightLeakUnderGrainAndHalationLandsOnTheSignal() {
    val film = Film(0.5f, 0.5f, leak = 0.25f, seed = OTHER_SEED)
    val tick = midRunTick(film, WIDTH, HEIGHT)

    assertLeakMatches(draw(film, WIDTH, HEIGHT, tick), film, tick, WIDTH, HEIGHT, CHAIN_TOLERANCE)
  }

  @Test
  fun aFullLeakUnderTheFlickerDustAndGateLandsOnTheSignal() {
    val film = Film(0f, 0f, flicker = 0.5f, dust = 0.5f, gate = 0.5f, leak = 1f)
    val tick = midRunTick(film, WIDTH, HEIGHT)

    assertLeakMatches(draw(film, WIDTH, HEIGHT, tick), film, tick, WIDTH, HEIGHT, TOLERANCE)
  }

  @Test
  fun aSeedOfItsOwnDrawsItsOwnLeak() {
    val film = Film(0f, 0f, leak = 0.5f)
    val seeded = Film(0f, 0f, leak = 0.5f, seed = OTHER_SEED)
    val tick = midRunTick(seeded, WIDTH, HEIGHT)

    val frame = draw(seeded, WIDTH, HEIGHT, tick)

    assertLeakMatches(frame, seeded, tick, WIDTH, HEIGHT, TOLERANCE)
    withClue("a seed of its own draws a different frame") {
      frame.contentEquals(draw(film, WIDTH, HEIGHT, tick)) shouldBe false
    }
  }

  @Test
  fun aSmallerPreviewDrawsTheSameLeakAtItsOwnSize() {
    val film = Film(0f, 0f, leak = 0.5f)
    val width = WIDTH * 3 / 4
    val height = HEIGHT * 3 / 4
    val tick = midRunTick(film, width, height)

    assertLeakMatches(draw(film, width, height, tick), film, tick, width, height, TOLERANCE)
  }

  @Test
  fun theSameTimeDrawsTheSameLeakAfterASeek() {
    val film = Film(0f, 0f, leak = 1f)
    val tick = midRunTick(film, WIDTH, HEIGHT)
    val first = draw(film, WIDTH, HEIGHT, tick)
    val elsewhere = draw(film, WIDTH, HEIGHT, tick + LEAK_STAGGER_TICKS)
    val again = draw(film, WIDTH, HEIGHT, tick)

    withClue("the leak moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun aBarUnderTheLeakStaysClear() {
    val film = Film(0f, 0f, leak = 1f)
    val tick = midRunTick(film, WIDTH, HEIGHT)
    val source = { x: Int, y: Int ->
      if (y < BAR_ROWS || y >= HEIGHT - BAR_ROWS) floatArrayOf(0f, 0f, 0f, 0f) else codesAt(x, y, WIDTH, HEIGHT)
    }
    val frame = draw(film, WIDTH, HEIGHT, tick, source)

    val bar =
      placesOf(WIDTH, HEIGHT)
        .filter { it.second < BAR_ROWS || it.second >= HEIGHT - BAR_ROWS }
        .maxBy { lightAt(it, film, tick, WIDTH, HEIGHT) }
    withClue("the leak is lit over the bar") { lightAt(bar, film, tick, WIDTH, HEIGHT) shouldBeGreaterThan LIT }
    withClue("the bar stays clear") {
      frame[indexOf(bar, WIDTH, HEIGHT) + 3].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    val inside =
      placesOf(WIDTH, HEIGHT)
        .filter { it.second in BAR_ROWS until HEIGHT - BAR_ROWS }
        .maxBy { lightAt(it, film, tick, WIDTH, HEIGHT) }
    assertPixel(frame, inside, WIDTH, HEIGHT, wantedAt(inside, film, tick, WIDTH, HEIGHT, null), TOLERANCE)
  }

  // Reads a grid, the leak's brightest place and a place it leaves dark when there is one.
  private fun assertLeakMatches(
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
    val lit = brightest(film, tick, width, height)
    withClue("the leak has to be lit for this to tell") {
      lightAt(lit, film, tick, width, height) shouldBeGreaterThan
        LIT
    }
    val points =
      rows.flatMap { y -> columns.map { x -> x to y } } + lit + listOfNotNull(unlit(film, tick, width, height))
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

  private fun lightAt(
    place: Pair<Int, Int>,
    film: Film,
    tick: Int,
    width: Int,
    height: Int,
  ): Double =
    FilmLeak
      .leakAt(
        (place.first + 0.5) / width,
        (place.second + 0.5) / height,
        width.toDouble() / height,
        tick,
        film.seed,
        film.leak.toDouble(),
      ).max()

  // Every fourth pixel each way, with the frame's last row and column.
  private fun placesOf(
    width: Int,
    height: Int,
  ): List<Pair<Int, Int>> {
    val columns = (0 until width step PLACE_STEP) + (width - 1)
    val rows = (0 until height step PLACE_STEP) + (height - 1)
    return rows.flatMap { y -> columns.map { x -> x to y } }
  }

  private fun brightest(
    film: Film,
    tick: Int,
    width: Int,
    height: Int,
  ): Pair<Int, Int> = placesOf(width, height).maxBy { lightAt(it, film, tick, width, height) }

  private fun unlit(
    film: Film,
    tick: Int,
    width: Int,
    height: Int,
  ): Pair<Int, Int>? = placesOf(width, height).firstOrNull { lightAt(it, film, tick, width, height) == 0.0 }

  // The first tick from START_TICK on where both spots are part way through their run and the leak leaves some of the
  // frame dark.
  private fun midRunTick(
    film: Film,
    width: Int,
    height: Int,
  ): Int =
    (START_TICK until START_TICK + SEARCH_TICKS step TICK_STEP).firstOrNull { tick ->
      FilmLeak.leakSpots(film.seed, tick).all { it.envelope in MID_ENVELOPE } &&
        unlit(film, tick, width, height) != null
    } ?: error("no tick in $SEARCH_TICKS from $START_TICK holds both spots mid run over a frame with a dark place")

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
    const val OTHER_SEED = 7
    const val START_TICK = 600
    const val SEARCH_TICKS = 2_400
    const val TICK_STEP = 5
    const val PLACE_STEP = 4
    const val BAR_ROWS = 60

    // A time inside the tick, clear of its opening.
    const val INTO_TICK = 0.01
    const val QUARTER = 256.0
    const val RAMP = 400.0
    const val STEPS = 1024.0
    const val BLUE = 0.25f
    const val LIFT = 0.05
    const val LIT = 0.1
    const val TOLERANCE = 1e-4

    // The halation and the last step each round to half float, 2^-11 from a half to one.
    const val CHAIN_TOLERANCE = 1e-3
    val MID_ENVELOPE = 0.2..0.8
    val PATCH = floatArrayOf(0.875f, 0.8125f, 0.75f)
  }
}
