package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.BEFORE_ZERO
import dev.jordond.filmstrip.ffmpeg.support.BEFORE_ZERO_SECONDS
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertFilmLanded
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.filmReadTick
import dev.jordond.filmstrip.ffmpeg.support.fragmentFrame
import dev.jordond.filmstrip.ffmpeg.support.fragmentOf
import dev.jordond.filmstrip.ffmpeg.support.graySource
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Film] with its weave, flicker, dust and gate on against [FilmSignal.pixelAt].
 *
 * The picture is a gray edge down the middle, so its chroma stays neutral and a weave moves the luma beside the edge.
 * The reads take in the frame's corners, where the gate closes, the two columns either side of the edge, and a speck
 * of dust and a scratch where the film's seed lands them.
 */
class FfmpegFilmGateMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `every control at a mid strength lands on the signal`() =
    runBlocking<Unit> { assertCompositionReadsSignal(MID, WIDTH, HEIGHT) }

  @Test
  fun `grain halation and flicker at a mid strength land on the signal`() =
    runBlocking<Unit> { assertCompositionReadsSignal(GLOWING_FLICKER, WIDTH, HEIGHT) }

  @Test
  fun `super 8 lands on the signal`() = runBlocking<Unit> { assertCompositionReadsSignal(Film.Super8, WIDTH, HEIGHT) }

  @Test
  fun `dust and a scratch land on the signal under a seed that marks the frame`() =
    runBlocking<Unit> {
      val film = Film(grain = 0.5f, halation = 0.5f, dust = 1f, gate = 0.5f, seed = markingSeed())
      assertCompositionReadsSignal(film, WIDTH, HEIGHT, marked = true)
    }

  @Test
  fun `a seed of its own lands on the signal`() =
    runBlocking<Unit> {
      val seeded = Film(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, seed = OTHER_SEED)
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      assertCompositionReadsSignal(seeded, WIDTH, HEIGHT)
      val ours = draw.planesAt(draw.composition(listOf(clip), listOf(seeded)), FILM_READ_SLOT)
      val theirs = draw.planesAt(draw.composition(listOf(clip), listOf(MID)), FILM_READ_SLOT)
      withClue("a seed of its own draws a different frame") {
        (0 until HEIGHT).any { y -> (0 until WIDTH).any { x -> ours.luma(x, y) != theirs.luma(x, y) } } shouldBe true
      }
    }

  @Test
  fun `a frame that lands before zero still reads the gate map`() {
    val frame = Size(WIDTH, HEIGHT)
    val source = graySource(frame, "if(lt(X,W/2),$LIGHT_LUMA,$DARK_LUMA)")
    val drawn = fragmentFrame(source + BEFORE_ZERO, frame, fragmentOf(FilterEffectResolver(), GATE_ONLY, frame))

    assertFilmLanded(
      drawn,
      fragmentFrame(source, frame),
      GATE_ONLY,
      edgePoints(WIDTH, HEIGHT),
      FilmSignal.grainTickAt(BEFORE_ZERO_SECONDS),
    )
  }

  @Test
  fun `a smaller preview draws the same look at its own size`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val spec = ExportSpec(heightPx = HEIGHT * 3 / 4)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(MID)), FILM_READ_SLOT, spec)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0, spec)

      assertFilmLanded(frame, plain, MID, edgePoints(frame.width, frame.height))
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::edgeAt)), listOf(Film.Super8))
      val at = (FILM_READ_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, FILM_READ_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip film after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, { BRIGHT }, listOf(crop, Film.Super8))
      val frame = draw.frameAt(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)), Duration.ZERO, 0)

      val barRow = HEIGHT / SQUARE_BAR_READ
      (0 until 3).forEach { channel ->
        withClue("channel $channel") {
          frame.channelAt(HEIGHT, HEIGHT / 2, barRow, channel).toDouble() shouldBe
            (SLATE_CODES[channel] plusOrMinus BAR_CODES)
        }
      }
    }

  private suspend fun assertCompositionReadsSignal(
    film: Film,
    width: Int,
    height: Int,
    marked: Boolean = false,
  ) {
    val clip = draw.clipOf(width, height, ::edgeAt)
    val marks = if (marked) markedPoints(film.seed, width, height) else emptyList()
    if (marked) withClue("the seed has to land a speck and a scratch for this to tell") { marks.size shouldBe 2 }
    assertFilmLanded(
      draw.planesAt(draw.composition(listOf(clip), listOf(film)), FILM_READ_SLOT),
      draw.planesAt(draw.composition(listOf(clip)), 0),
      film,
      edgePoints(width, height) + marks,
    )
  }

  private fun edgePoints(
    width: Int,
    height: Int,
  ) = listOf(width / 2 - 1 to height / 3, width / 2 to height / 3, 0 to 0, width - 1 to height - 1)

  // A pixel under a speck of dust and one under a scratch at the read's tick, where both land.
  private fun markedPoints(
    seed: Int,
    width: Int,
    height: Int,
  ): List<Pair<Int, Int>> {
    val tick = filmReadTick()
    val scratch =
      (0 until width).firstOrNull { FilmSignal.scratchAt(seed, tick, (it + 0.5) / width, 1.0) != 0.0 }?.let {
        it to height / 2
      }
    val inside = (height / 8 until height - height / 8).asSequence()
    val speck =
      inside
        .flatMap { y -> (width / 8 until width - width / 8).asSequence().map { x -> x to y } }
        .firstOrNull { (x, y) -> FilmSignal.dustAt(seed, tick, FilmSignal.grainCell(x, y, width, height), 1.0) != 0.0 }
    return listOfNotNull(speck, scratch)
  }

  // The first seed from 1 that lands a speck and a scratch inside the frame at the read's tick.
  private fun markingSeed(): Int = (1..SEED_SEARCH).first { markedPoints(it, WIDTH, HEIGHT).size == 2 }

  private fun edgeAt(x: Double): DoubleArray = DoubleArray(3) { if (x < 0.5) LIGHT else DARK }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val OTHER_SEED = 7
    const val SEED_SEARCH = 5_000
    const val LIGHT = 0.875
    const val DARK = 0.375

    // The two sides of the edge a graph source draws, as limited range luma codes.
    const val LIGHT_LUMA = 208
    const val DARK_LUMA = 98
    const val SQUARE_BAR_READ = 16
    const val BAR_CODES = 3.0
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())
    val BRIGHT = DoubleArray(3) { FilmSignal.HALATION_FULL_LUMA }
    val MID = Film(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
    val GLOWING_FLICKER = Film(grain = 0.5f, halation = 0.5f, flicker = 0.5f)

    // The gate on its own, so a read lands on its map and nothing else.
    val GATE_ONLY = Film(grain = 0f, halation = 0f, gate = 0.5f)
  }
}
