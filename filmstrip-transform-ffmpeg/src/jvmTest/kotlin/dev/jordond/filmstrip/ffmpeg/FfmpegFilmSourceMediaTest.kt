package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.ffmpeg.support.FILM_READ_SLOT
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertFilmLanded
import dev.jordond.filmstrip.ffmpeg.support.planeCodesAt
import dev.jordond.filmstrip.ffmpeg.support.planeCodesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test

/**
 * Reads a [Film] against the signal where a source's format or colour could take it off.
 *
 * The fragment's expressions are written in 8 bit limited BT.709 codes, whatever a clip's chain hands it.
 */
class FfmpegFilmSourceMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a clip film lands on the signal from a ten bit source`() =
    runBlocking<Unit> { assertClipScopeReadsSignal("yuv420p10le", "tv") }

  @Test
  fun `the sd picture sits further from its displayed colour than a read can tell`() {
    val displayed = planeCodesOf(displayedAt(1.0))
    val source = planeCodesOf(edgeAt(1.0))
    val shifted = displayed.indices.maxOf { abs(displayed[it] - source[it]) }

    shifted shouldBeGreaterThan SD_CODES
  }

  @Test
  fun `a clip film on an sd clip draws over the corrected colour`() =
    runBlocking<Unit> {
      val film = Film()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(film), onSmpteC = true)

      assertSdReadsSignal(film, draw.composition(listOf(clip)))
    }

  @Test
  fun `a composition film over an sd clip draws over the corrected colour`() =
    runBlocking<Unit> {
      val film = Film()
      val clip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, onSmpteC = true)

      assertSdReadsSignal(film, draw.composition(listOf(clip), listOf<EffectSpec>(film)))
    }

  private suspend fun assertClipScopeReadsSignal(
    pixelFormat: String,
    range: String,
  ) {
    val film = Film()
    val filmed = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, listOf(film), pixelFormat, range)
    val plain = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, pixelFormat = pixelFormat, range = range)
    val frame = draw.planesAt(draw.composition(listOf(filmed)), FILM_READ_SLOT)

    assertFilmLanded(frame, draw.planesAt(draw.composition(listOf(plain)), 0), film)
  }

  private suspend fun assertSdReadsSignal(
    film: Film,
    composition: EditComposition,
  ) {
    val plainClip = draw.clipOf(WIDTH, HEIGHT, ::edgeAt, onSmpteC = true)
    val plain = draw.planesAt(draw.composition(listOf(plainClip)), 0)
    listOf(WIDTH / 4, 3 * WIDTH / 4).forEach { x ->
      val wanted = planeCodesOf(displayedAt((x + 0.5) / WIDTH))
      val read = plain.planeCodesAt(x, HEIGHT / 2)
      wanted.indices.forEach { plane ->
        withClue("plain column $x plane $plane read ${read[plane]} against ${wanted[plane]}") {
          abs(read[plane] - wanted[plane]) shouldBeLessThan SD_CODES
        }
      }
    }
    assertFilmLanded(draw.planesAt(composition, FILM_READ_SLOT), plain, film)

    val graph = draw.graphOf(composition)
    graph shouldContain SD_HEAD
    withClue("the head runs ahead of the film and the tags after it") {
      graph.indexOf(SD_HEAD) shouldBeLessThan graph.indexOf(FILM)
      graph.lastIndexOf(BT709_TAGS) shouldBeGreaterThan graph.lastIndexOf(FILM)
    }
  }

  // A bright side on the response's ramp over a midtone with green holding it up, which SD primaries move the furthest.
  private fun edgeAt(x: Double): DoubleArray = if (x < 0.5) PATCH.copyOf() else GROUND.copyOf()

  private fun displayedAt(x: Double): DoubleArray = sdSourceDisplayed(edgeAt(x), SMPTE_C_PRIMARIES)

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360

    // How far a plain sd render's planes sit from its displayed colour, which holds the fixture's own 8 bit chroma. The
    // most measured is 0.68 on ffmpeg 8.0.1 x86_64 and 9.0.2 arm64, where the source's own colour sits 6.1 off.
    const val SD_CODES = 1.5
    const val SD_HEAD = "colorspace=ispace"
    const val FILM = "gblur"
    const val BT709_TAGS = "setparams=color_primaries=bt709"
    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.15, 0.6, 0.2)
  }
}
