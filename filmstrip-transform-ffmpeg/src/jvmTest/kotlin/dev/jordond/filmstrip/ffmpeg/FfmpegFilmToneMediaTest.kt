package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.FilmTone
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertFilmLanded
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.filmReadTick
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Film]'s fade and breath against the signal.
 *
 * The picture ramps across from a warm dark code to a cool bright one, so the tone is read on the middle of the code
 * range in every channel. Every test first checks that [FilmTone.toned] moves a mid code at the tick it reads, so a
 * seed that draws next to nothing there fails the test rather than passing it on nothing.
 */
class FfmpegFilmToneMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a mid fade alone lands on the signal`() =
    runBlocking<Unit> { assertToneReadsSignal(Film(grain = 0f, halation = 0f, fade = 0.5f)) }

  @Test
  fun `a mid fade under the flicker lands on the signal`() =
    runBlocking<Unit> { assertToneReadsSignal(Film(grain = 0f, halation = 0f, flicker = 0.5f, fade = 0.5f)) }

  @Test
  fun `a breath between two knots lands on the signal`() =
    runBlocking<Unit> {
      (filmReadTick() % FilmTone.KNOT_TICKS) shouldNotBe 0
      assertToneReadsSignal(Film(grain = 0f, halation = 0f, breath = 0.6f, seed = OTHER_SEED))
    }

  @Test
  fun `a breath on a knot lands on the signal`() =
    runBlocking<Unit> {
      val tick = FilmSignal.grainTickAt(KNOT_SLOT.toDouble() / FILTER_RATE)

      (tick % FilmTone.KNOT_TICKS) shouldBe 0
      assertToneReadsSignal(Film(grain = 0f, halation = 0f, breath = 0.6f, seed = OTHER_SEED), slot = KNOT_SLOT)
    }

  @Test
  fun `a mid fade alone under the halation lands on the signal`() =
    runBlocking<Unit> { assertToneReadsSignal(Film(grain = 0f, halation = 0.5f, fade = 0.5f)) }

  @Test
  fun `a fade and a breath under grain and halation land on the signal`() =
    runBlocking<Unit> {
      assertToneReadsSignal(Film(grain = 0.5f, halation = 0.5f, breath = 0.6f, fade = 0.5f, seed = OTHER_SEED))
    }

  @Test
  fun `a fade and a breath on a woven frame land on the signal`() =
    runBlocking<Unit> {
      assertToneReadsSignal(
        Film(grain = 0f, halation = 0f, weave = 0.5f, breath = 0.6f, fade = 0.5f, seed = OTHER_SEED),
      )
    }

  @Test
  fun `a smaller preview draws the same tone at its own size`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0f, breath = 0.6f, fade = 0.5f, seed = OTHER_SEED)

      assertToneReadsSignal(film, ExportSpec(heightPx = HEIGHT * 3 / 4))
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0f, breath = 1f, fade = 0.5f, seed = OTHER_SEED)
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt)), listOf(film))
      val at = (FILM_READ_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, FILM_READ_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip fade after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0f, fade = 1f)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt, listOf(crop, film))
      val frame = draw.frameAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), Duration.ZERO, FILM_READ_SLOT)

      // A wide picture on a square canvas leaves a bar above and below, and the canvas is the clip's height a side.
      (0 until 3).forEach { channel ->
        withClue("channel $channel") {
          frame.channelAt(HEIGHT, HEIGHT / 2, BAR_ROW, channel).toDouble() shouldBe
            (SLATE_CODES[channel] plusOrMinus BAR_CODES)
        }
      }
    }

  private suspend fun assertToneReadsSignal(
    film: Film,
    spec: ExportSpec = ExportSpec(),
    slot: Int = FILM_READ_SLOT,
  ) {
    val tick = FilmSignal.grainTickAt(slot.toDouble() / FILTER_RATE)
    val clip = draw.clipOf(WIDTH, HEIGHT, ::rampAt)
    val frame = draw.planesAt(draw.composition(listOf(clip), listOf(film)), slot, spec)
    val plain = draw.planesAt(draw.composition(listOf(clip)), 0, spec)

    withClue("the tone has to move a mid code for this to tell") {
      val mid = rampAt(0.5)
      val toned = FilmTone.toned(mid[0], mid[1], mid[2], tick, FilmParams.of(film))
      mid.indices.maxOf { abs(toned[it] - mid[it]) } * FULL shouldBeGreaterThan MOVED_CODES
    }
    assertFilmLanded(frame, plain, film, tick = tick)
  }

  private fun rampAt(x: Double): DoubleArray = doubleArrayOf(0.25 + 0.5 * x, 0.5, 0.75 - 0.5 * x)

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val OTHER_SEED = 68
    const val KNOT_SLOT = 15
    const val FULL = 255.0
    const val MOVED_CODES = 3.0
    const val BAR_ROW = 16
    const val BAR_CODES = 3.0
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())
  }
}
