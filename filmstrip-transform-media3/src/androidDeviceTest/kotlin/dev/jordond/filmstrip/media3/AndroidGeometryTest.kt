package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.Crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Composition-level geometry, checked against the pixels a real export writes.
 *
 * Every clip here is 16:9, so a square [Crop] at [Fit.Crop] has to take a region away and a square
 * one at [Fit.Contain] has to leave bars instead. Reading the same point on both is what separates a
 * crop that ran from one that resolved to nothing, which a plan alone cannot tell apart.
 */
class AndroidGeometryTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  // A square crop measured against a square output finds no aspect to trim, resolves to nothing,
  // and leaves the 16:9 frame letterboxed into the square instead. The contained export is the
  // control: it proves the sample point sits where a bar would be, so the cropped export reading
  // anything else is the crop having really run.
  @Test
  fun aCompositionCropTakesTheFrameDownRatherThanLettingItBeLetterboxed() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val contained = frame(export(source, Fit.Contain))
      val cropped = frame(export(source, Fit.Crop))

      val bar = contained.averageAt(CENTRE_X, EDGE_Y)
      assertTrue(distance(bar, MAGENTA) < COLOUR_TOLERANCE, "the contained export left no bar to compare against")

      val edge = cropped.averageAt(CENTRE_X, EDGE_Y)
      assertTrue(distance(edge, MAGENTA) > COLOUR_TOLERANCE, "the cropped export still has a fill bar at $edge")
    }

  @Test
  fun aCompositionCropWritesTheSquareFrameItPlanned() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val file = export(source, Fit.Crop)
      val frame = frame(file)

      assertEquals(Size(frame.width, frame.height), SQUARE, "the export did not write the frame the plan named")
    }

  private suspend fun export(
    source: MediaSource,
    fit: Fit,
  ): File {
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(source)))),
        effects = listOf(Crop(AspectRatio.Square, fit = fit)),
        fill = Fill.Solid(MAGENTA_ARGB),
      )
    val plan =
      when (val verdict = filmstrip.plan(composition, ExportSpec())) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    return File((assertIs<ExportStatus.Success>(finished).output as MediaSink.Path).path)
  }

  private fun frame(video: File): Bitmap = frameOf(video, MID_CLIP)

  private fun fixture(name: String = CLIP): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(name) ?: return null
    val file = File(context.cacheDir, name)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private companion object {
    val TIMEOUT = 5.minutes
    val MID_CLIP = 1_000.milliseconds

    const val CLIP = "android_export_a.mp4"

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
