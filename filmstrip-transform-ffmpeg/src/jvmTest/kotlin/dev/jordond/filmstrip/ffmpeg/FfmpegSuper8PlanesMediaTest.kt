package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.assertFormsAgree
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Super 8, a Film with every control set, drawn from live planes reads no differing sample against its `geq` graph.
 * The drawn graph takes most of a minute for one 1080p frame, so the long run of ticks is read on a small raster and
 * the large raster for three frames.
 */
class FfmpegSuper8PlanesMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `super 8 draws the same grain and marks live across a second of ticks`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(640, 360)))
      draw.assertFormsAgree(plan, frames = 24)
    }

  @Test
  fun `super 8 at 720p draws the same grain and marks live where no cell is whole`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(1280, 720)))
      draw.assertFormsAgree(plan, frames = 3)
    }

  @Test
  fun `super 8 at 1080p draws the same grain and marks live`() =
    runBlocking<Unit> {
      val plan = draw.composition(listOf(clip(1920, 1080)))
      draw.assertFormsAgree(plan, frames = 3)
    }

  private fun clip(
    width: Int,
    height: Int,
  ) = draw.clipOf(width, height, ::ramp, listOf(Film.Super8))

  private fun ramp(fraction: Double): DoubleArray = doubleArrayOf(fraction, 0.5, 1.0 - fraction)
}
