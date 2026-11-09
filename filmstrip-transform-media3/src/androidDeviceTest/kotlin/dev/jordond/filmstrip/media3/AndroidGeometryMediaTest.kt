package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Composition-level geometry, checked against the pixels a real export writes.
 *
 * Every clip here is 16:9, so a square [Crop] at [Fit.Crop] has to take a region away and a square
 * one at [Fit.Contain] has to leave bars instead. Reading the same point on both is what separates a
 * crop that ran from one that resolved to nothing, which a plan alone cannot tell apart.
 */
class AndroidGeometryMediaTest {
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
      withClue("the contained export left no bar to compare against") {
        (distance(bar, MAGENTA) < COLOR_TOLERANCE) shouldBe true
      }

      val edge = cropped.rgbAt(CENTRE_X, EDGE_Y)
      withClue("the cropped export still has a fill bar at $edge") {
        (distance(edge, MAGENTA) > COLOR_TOLERANCE) shouldBe true
      }
    }

  @Test
  fun aCompositionCropWritesTheSquareFrameItPlanned() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      val frame = probe.frame(probe.export(composition(source, Fit.Crop), ExportSpec()))

      withClue("the export did not write the frame the plan named") { SQUARE shouldBe Size(frame.width, frame.height) }
    }

  private fun composition(
    source: MediaSource,
    fit: Fit,
  ) = Composition(
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

    const val COLOR_TOLERANCE = 30
  }
}
