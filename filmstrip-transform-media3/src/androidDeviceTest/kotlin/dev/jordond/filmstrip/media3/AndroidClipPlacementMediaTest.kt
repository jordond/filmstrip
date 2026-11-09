package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Exports whose clips place themselves through a letterboxing or stretching crop, read back from the written file.
 *
 * The fill is a color the fixture's bars never show, so a bar cannot be mistaken for the picture. Every frame is read
 * the way its container shows it, since media3 may encode a portrait frame turned on its side.
 */
class AndroidClipPlacementMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aClipsOwnLetterboxingCropWritesTheFillIntoItsCanvasBars() =
    runTest(timeout = TIMEOUT) {
      listOf(AspectRatio.Square, AspectRatio.Portrait).forEach { aspect ->
        val crop = Crop(aspect, Fit.Contain)
        val composition = composition(clipEffects = listOf(crop))
        val plan = probe.plan(composition)
        val written = probe.writtenBy(probe.finish(plan))
        val content = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect

        withClue("a canvas of $aspect, written turned ${written.rotationDegrees} degrees") {
          isSlate(written.rgbAt(X, content.top / 2)) shouldBe true
          isSlate(written.rgbAt(X, (1 + content.bottom) / 2)) shouldBe true
          isSlate(written.rgbAt(X, (content.top + content.bottom) / 2)) shouldBe false
        }
      }
    }

  // A preview lowers the same edit at a smaller height, and the placement is measured against the natural frame, so
  // the content lands on the same fraction of the frame at both heights.
  @Test
  fun aLetterboxingCropPlacesItsContentOnTheSameRectAtAPreviewHeight() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(AspectRatio.Square, Fit.Contain)
      val composition = composition(clipEffects = listOf(crop))
      val exportPlan = probe.plan(composition)
      val previewPlan = probe.plan(composition, ExportSpec(heightPx = PREVIEW_HEIGHT))
      withClue("the preview has to be lowered smaller than the export for this to tell") {
        (previewPlan.output.size.height < exportPlan.output.size.height) shouldBe true
      }
      val content = placementOf(SOURCE, listOf(listOf(crop)), exportPlan.output.size).drawnRect

      listOf(exportPlan, previewPlan).forEach { plan ->
        val written = probe.writtenBy(probe.finish(plan))

        fun reads(
          what: String,
          y: Float,
          bar: Boolean,
        ) {
          val read = written.rgbAt(X, y)
          withClue("${plan.output.size.height} rows, $what read $read") { isSlate(read) shouldBe bar }
        }

        reads("the bar above", content.top - NEAR_EDGE, bar = true)
        reads("the content's top", content.top + NEAR_EDGE, bar = false)
        reads("the content's bottom", content.bottom - NEAR_EDGE, bar = false)
        reads("the bar below", content.bottom + NEAR_EDGE, bar = true)
      }
    }

  @Test
  fun aStretchingCropFillsTheFrameAndLeavesNoBar() =
    runTest(timeout = TIMEOUT) {
      val written = probe.written(composition(clipEffects = listOf(Crop(AspectRatio.Square, Fit.Stretch))))

      isSlate(written.rgbAt(X, EDGE)) shouldBe false
      isSlate(written.rgbAt(X, 1 - EDGE)) shouldBe false
    }

  // The composition's crop reads the square canvas the clip's crop made, bars included. Cropping the content alone
  // would leave a frame with no bar in it at all.
  @Test
  fun aCropAfterALetterboxingCropCutsTheCanvasBarsIncluded() =
    runTest(timeout = TIMEOUT) {
      val written =
        probe.written(
          composition(
            clipEffects = listOf(Crop(AspectRatio.Square, Fit.Contain)),
            effects = listOf(CropRect(NormalizedRect(0f, 0f, 1f, 0.5f))),
          ),
        )

      val top = written.rgbAt(0.5f, EDGE)
      withClue("the top of the canvas read $top") { isSlate(top) shouldBe true }
      isSlate(written.rgbAt(0.5f, 0.75f)) shouldBe false
    }

  private fun composition(
    clipEffects: List<EffectSpec>,
    effects: List<EffectSpec> = emptyList(),
  ) = Composition(
    tracks = listOf(Track(listOf(Clip(probe.fixture(), effects = clipEffects)))),
    effects = effects,
    fill = Fill.Solid(SLATE_ARGB),
  )

  private fun isSlate(pixel: Triple<Int, Int, Int>): Boolean = distance(pixel, SLATE) < COLOR_TOLERANCE

  private companion object {
    // The fixture's own frame.
    val SOURCE = Size(640, 360)

    // The middle of the fixture's fourth bar, clear of the seam between two.
    const val X = 0.4375f

    // Two percent in from an edge, clear of the encoder's own edge blocks.
    const val EDGE = 0.02f

    // A height a preview lowers the edit at, under the export's own.
    const val PREVIEW_HEIGHT = 144

    // Five percent from an edge, clear of the chroma a small 4:2:0 encode smears across it.
    const val NEAR_EDGE = 0.05f

    const val SLATE_ARGB = 0xFF204060.toInt()
    val SLATE = Triple(0x20, 0x40, 0x60)
    const val COLOR_TOLERANCE = 30
  }
}
