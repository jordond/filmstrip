package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composition-level geometry, checked against the pixels a real export writes.
 *
 * Every clip here is 16:9, so a square [Crop] at [Fit.Crop] has to take a region away and a square
 * one at [Fit.Contain] has to leave bars instead. Reading the same point on both is what separates a
 * crop that ran from one that resolved to nothing, which a plan alone cannot tell apart.
 */
class AndroidGeometryTest {
  private val probe = Media3ExportProbe()

  // A square crop measured against a square output finds no aspect to trim, resolves to nothing,
  // and leaves the 16:9 frame letterboxed into the square instead. The contained export is the
  // control: it proves the sample point sits where a bar would be, so the cropped export reading
  // anything else is the crop having really run.
  @Test
  fun aCompositionCropTakesTheFrameDownRatherThanLettingItBeLetterboxed() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      val contained = probe.written(composition(source, Fit.Contain), ExportSpec())
      val cropped = probe.written(composition(source, Fit.Crop), ExportSpec())

      val bar = contained.rgbAt(CENTRE_X, EDGE_Y)
      assertTrue(distance(bar, MAGENTA) < COLOUR_TOLERANCE, "the contained export left no bar to compare against")

      val edge = cropped.rgbAt(CENTRE_X, EDGE_Y)
      assertTrue(distance(edge, MAGENTA) > COLOUR_TOLERANCE, "the cropped export still has a fill bar at $edge")
    }

  @Test
  fun aCompositionCropWritesTheSquareFrameItPlanned() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      val frame = probe.frame(probe.export(composition(source, Fit.Crop), ExportSpec()))

      assertEquals(Size(frame.width, frame.height), SQUARE, "the export did not write the frame the plan named")
    }

  private fun composition(
    source: MediaSource,
    fit: Fit,
  ) = EditComposition(
    tracks = listOf(Track(listOf(Clip(source)))),
    effects = listOf(Crop(AspectRatio.Square, fit = fit)),
    fill = Fill.Solid(MAGENTA_ARGB),
  )

  private companion object {
    // The fixture is 640x360, so a square crop of it keeps its full height.
    val SQUARE = Size(360, 360)

    // Inside the bar a square Fit.Contain crop of a 16:9 clip leaves along its top edge.
    const val EDGE_Y = 0.05f
    const val CENTRE_X = 0.5f

    const val MAGENTA_ARGB = 0xFFFF00FF.toInt()
    val MAGENTA = Triple(255, 0, 255)

    const val COLOUR_TOLERANCE = 30
  }
}
