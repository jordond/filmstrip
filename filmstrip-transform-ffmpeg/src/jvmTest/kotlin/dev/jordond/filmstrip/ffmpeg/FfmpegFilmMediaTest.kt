package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.HalationGlow
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertFilmLanded
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.filmReads
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the frames ffmpeg draws for a [Film] straight off the preview pump against [FilmSignal], with no encoder in the
 * way, and what it reaches at each scope.
 *
 * The picture is flat columns on either side of an edge, which a lossless clip holds exactly.
 */
class FfmpegFilmMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a composition film lands on the signal at a mid strength`() =
    runBlocking<Unit> { assertCompositionReadsSignal(Film(), WIDTH, HEIGHT) }

  @Test
  fun `a clip film lands on the signal at a mid strength`() =
    runBlocking<Unit> {
      val film = Film()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(film))
      val plain = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      assertFilmLanded(
        draw.planesAt(draw.composition(listOf(clip)), FILM_READ_SLOT),
        draw.planesAt(draw.composition(listOf(plain)), 0),
        film,
      )
    }

  @Test
  fun `grain alone lands on the signal and halation alone glows past the edge`() =
    runBlocking<Unit> {
      assertCompositionReadsSignal(Film(grain = 0.5f, halation = 0f), WIDTH, HEIGHT)
      assertCompositionReadsSignal(Film(grain = 0f, halation = 0.5f), WIDTH, HEIGHT)
    }

  @Test
  fun `a read tells half the grain or most of the glow from the film that was drawn`() =
    runBlocking<Unit> {
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(Film())), FILM_READ_SLOT)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)

      listOf(Film(grain = 0.25f), Film(halation = 0.35f)).forEach { wrong ->
        withClue("$wrong read against a default film") {
          filmReads(frame, plain, wrong).any { it.miss >= it.limit } shouldBe true
        }
      }
    }

  @Test
  fun `a chroma edge comes back sharp through gbrap with no glow to add`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 0.5f)
      val edge = WIDTH / 4
      val clip =
        draw.clipOfPlanes(
          WIDTH,
          HEIGHT,
          { EDGE_LUMA },
          { if (it < edge) EDGE_CB[0] else EDGE_CB[1] },
          { if (it < edge) EDGE_CR[0] else EDGE_CR[1] },
        )
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0)
      val filmed = draw.planesAt(draw.composition(listOf(clip), listOf(film)), 0)

      listOf(edge - 2, edge - 1, edge, edge + 1).forEach { column ->
        withClue("chroma column $column") {
          abs(filmed.cb(column, HEIGHT / 4) - plain.cb(column, HEIGHT / 4)) shouldBeLessThanOrEqual EDGE_CODES
          abs(filmed.cr(column, HEIGHT / 4) - plain.cr(column, HEIGHT / 4)) shouldBeLessThanOrEqual EDGE_CODES
        }
      }
      withClue("luma beside the edge") {
        abs(filmed.luma(edge * 2, HEIGHT / 2) - plain.luma(edge * 2, HEIGHT / 2)) shouldBeLessThanOrEqual EDGE_CODES
      }
    }

  @Test
  fun `a smaller preview draws the same look at its own size`() =
    runBlocking<Unit> {
      val film = Film()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt)
      val spec = ExportSpec(heightPx = HEIGHT / 2)
      val frame = draw.planesAt(draw.composition(listOf(clip), listOf(film)), FILM_READ_SLOT, spec)
      val plain = draw.planesAt(draw.composition(listOf(clip)), 0, spec)

      assertFilmLanded(frame, plain, film)
    }

  @Test
  fun `a 4K frame draws its grain on whole cells and widens the map by neighbour`() =
    runBlocking<Unit> {
      withClue("a cell has to span whole pixels for this to tell") {
        (FilmSignal.grainCellSpan(UHD_WIDTH, UHD_HEIGHT) % FULL_HD_SPAN) shouldBe 0
      }
      assertCompositionReadsSignal(Film(grain = 0.5f, halation = 0f), UHD_WIDTH, UHD_HEIGHT)
    }

  @Test
  fun `a large frame blurs at the plans smaller size`() =
    runBlocking<Unit> {
      withClue("the plan has to scale for this to tell") {
        (FilmSignal.halationBlurPlan(LARGE_WIDTH).factor > 1) shouldBe true
      }
      assertCompositionReadsSignal(Film(), LARGE_WIDTH, LARGE_HEIGHT)
    }

  @Test
  fun `a 1080p frame blurs at a factor past two`() =
    runBlocking<Unit> {
      withClue("the plan has to scale past two for this to tell") {
        (FilmSignal.halationBlurPlan(FULL_HD_WIDTH).factor > 2) shouldBe true
      }
      assertCompositionReadsSignal(Film(), FULL_HD_WIDTH, FULL_HD_HEIGHT)
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val film = Film()
      val composition = draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::edgeAt)), listOf(film))
      val at = slotTime(FILM_READ_SLOT)

      val fromStart = draw.frames(composition, Duration.ZERO, FILM_READ_SLOT + 1).last()
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  @Test
  fun `a clip film after a contain crop keeps its alpha and leaves the bar as the fill`() =
    runBlocking<Unit> {
      val film = Film(grain = 1f, halation = 1f)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, { BRIGHT }, listOf(crop, film))
      val frame = frameOf(draw.composition(listOf(clip), fill = Fill.Solid(SLATE)))

      val height = HEIGHT
      val barRow = height / SQUARE_BAR_READ
      (0 until 3).forEach { channel ->
        withClue("channel $channel") {
          frame.channelAt(height, height / 2, barRow, channel).toDouble() shouldBe
            (SLATE_CODES[channel] plusOrMinus BAR_CODES)
        }
      }
    }

  @Test
  fun `a composition film glows from the picture into a bar`() =
    runBlocking<Unit> {
      val film = Film(grain = 0f, halation = 1f)
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)
      val clip = draw.clipOf(WIDTH, HEIGHT, { BRIGHT })
      val frame = frameOf(draw.composition(listOf(clip), listOf(crop, film), Fill.Solid(BLACK)))

      val plan = FilmSignal.halationBlurPlan(HEIGHT)
      // The picture fills the square's width, so bars run above and below it.
      val picture = HEIGHT * HEIGHT / WIDTH
      val barTop = (HEIGHT - picture) / 2
      val inBar = barTop - (plan.sigmaPx * plan.factor).toInt()
      val lift = frame.channelAt(HEIGHT, HEIGHT / 2, inBar, 0).toDouble()
      val glow =
        HalationGlow.of(HEIGHT, HEIGHT) { _, y ->
          if (y in barTop until barTop + picture) BRIGHT else doubleArrayOf(0.0, 0.0, 0.0)
        }
      val wanted = FilmSignal.halated(0.0, 0.0, 0.0, glow.at(HEIGHT / 2, inBar), FilmParams.of(film))[0] * FULL

      withClue("one sigma into the bar read $lift against $wanted") {
        wanted shouldBeGreaterThan BAR_GLOW_CODES * 2
        abs(lift - wanted) shouldBeLessThan BAR_GLOW_CODES
      }
    }

  private suspend fun assertCompositionReadsSignal(
    film: Film,
    width: Int,
    height: Int,
  ) {
    val clip = draw.clipOf(width, height, ::edgeAt)
    assertFilmLanded(
      draw.planesAt(draw.composition(listOf(clip), listOf(film)), FILM_READ_SLOT),
      draw.planesAt(draw.composition(listOf(clip)), 0),
      film,
    )
  }

  private suspend fun frameOf(
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
  ): ByteArray = draw.frames(composition, Duration.ZERO, FILM_READ_SLOT + 1, spec).last()

  private fun slotTime(slot: Int): Duration = (slot.toDouble() / FILTER_RATE).seconds

  // A bright side on the response's ramp over a midtone. A column's codes, from the fraction of the width it sits at.
  private fun edgeAt(x: Double): DoubleArray = if (x < 0.5) PATCH.copyOf() else GROUND.copyOf()

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val UHD_WIDTH = 3840
    const val UHD_HEIGHT = 2160

    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080

    // The span of a frame whose cells are one pixel, which a span of a whole number of cells divides.
    val FULL_HD_SPAN = FilmSignal.grainCellSpan(FULL_HD_WIDTH, FULL_HD_HEIGHT)
    const val SQUARE_BAR_READ = 16

    // Plane codes either side of a chroma edge, all of it inside the RGB gamut and under the response's low threshold.
    const val EDGE_LUMA = 80
    val EDGE_CB = listOf(102, 170)
    val EDGE_CR = listOf(150, 110)
    const val EDGE_CODES = 1

    const val BAR_CODES = 3.0

    // How far a read through RGBA sits from the glow one sigma into a bar.
    const val BAR_GLOW_CODES = 2.5

    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
    val BRIGHT = DoubleArray(3) { FilmSignal.HALATION_FULL_LUMA }
    const val BLACK = 0xFF000000.toInt()
    const val SLATE = 0xFF2F4F6F.toInt()
    val SLATE_CODES = listOf(0x2F.toDouble(), 0x4F.toDouble(), 0x6F.toDouble())
  }
}
