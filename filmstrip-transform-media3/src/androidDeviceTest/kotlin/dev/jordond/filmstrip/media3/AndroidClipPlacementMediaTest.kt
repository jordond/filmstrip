package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
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

  // A cut of the fixture that is 640 by 359.44 pixels, so the whole pixels it keeps are the planner's to say. The frame
  // written is the one the plan reports, whatever the encoder has to align it to.
  @Test
  fun aClipCropToAFrameThatIsNotWholeIsWrittenAtThePlannedSize() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(AspectRatio(CUT_WIDE, CUT_HIGH))
      val cut = crop.retainedRect(SOURCE).applyTo(SOURCE)
      withClue("the cut has to be a whole pixel short of a fraction for this to tell") {
        cut shouldBe Size(SOURCE.width, SOURCE.height - 1)
      }

      val plan = probe.plan(composition(clipEffects = listOf(crop)), ExportSpec())
      val finished = probe.finish(plan)

      val written =
        finished.info.video
          .shouldNotBeNull()
          .displaySize
      withClue("planned ${plan.output.size}, cut $cut, written $written") { written shouldBe plan.output.size }
    }

  // The canvas's aspect and the frame's height make the frame, whichever of a landscape and a portrait clip is first.
  // The frame is the plan's, the same for both orders, and its edges hold what the plan places there: a bar where the
  // content stays clear of the edge and the picture where it reaches it.
  @Test
  fun aCanvasCropAndAScaleWriteTheFrameOfTheAspectWhicheverClipComesFirst() =
    runTest(timeout = TIMEOUT) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        val plans = mutableListOf<Size>()
        for (order in listOf(listOf(CLIP, ROTATED), listOf(ROTATED, CLIP))) {
          val effects = listOf(Crop(aspect, Fit.Contain), Scale(FRAME_HEIGHT))
          val composition =
            Composition(
              tracks = listOf(Track(order.map { Clip(probe.fixture(it)) })),
              effects = effects,
              fill = Fill.Solid(SLATE_ARGB),
            )

          val plan = probe.plan(composition, ExportSpec())
          val finished = probe.finish(plan)
          val size =
            finished.info.video
              .shouldNotBeNull()
              .displaySize
          val first =
            probe
              .infoOf(probe.fixture(order.first()))
              .video
              .shouldNotBeNull()
              .displaySize
          val content = placementOf(first, listOf(emptyList(), effects), plan.output.size).drawnRect

          withClue("$aspect from ${order.first()}: planned ${plan.output.size}, written $size") {
            size shouldBe plan.output.size
          }
          assertEdges("$aspect from ${order.first()}", probe.writtenBy(finished), plan.output.size, content)
          plans += plan.output.size
        }
        withClue("$aspect over both orders planned $plans") { plans.toSet().size shouldBe 1 }
      }
    }

  // The first row, last row, first column and last column of the frame, each one pixel wide, each a bar where the
  // content stays two pixels or more clear of it and the picture where the content reaches it. A column or row of
  // padding the frame should not have, or one it lost, flips one of the four.
  private fun assertEdges(
    name: String,
    written: Written,
    frame: Size,
    content: NormalizedRect,
  ) {
    val edges =
      listOf(
        Triple("first row", content.top * frame.height, written.pixelAt(frame.width / 2, 0)),
        Triple("last row", (1 - content.bottom) * frame.height, written.pixelAt(frame.width / 2, frame.height - 1)),
        Triple("first column", content.left * frame.width, written.pixelAt(0, frame.height / 2)),
        Triple("last column", (1 - content.right) * frame.width, written.pixelAt(frame.width - 1, frame.height / 2)),
      )
    for ((edge, barPixels, read) in edges) {
      withClue("$name, $edge read $read with a bar of $barPixels pixels") {
        when {
          barPixels >= BAR_PIXELS -> isSlate(read) shouldBe true
          barPixels < REACHED_PIXELS -> isSlate(read) shouldBe false
        }
      }
    }
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

    // An aspect a hair wider than the fixture's, which keeps its whole width and all but a pixel of its height.
    const val CUT_WIDE = 641
    const val CUT_HIGH = 360

    const val CLIP = Media3ExportProbe.CLIP
    const val ROTATED = "android_export_rotated.mp4"
    const val FRAME_HEIGHT = 324

    // A bar this many pixels deep covers the edge pixel and the chroma it shares, and a bar under the second is the
    // content's.
    const val BAR_PIXELS = 2f
    const val REACHED_PIXELS = 0.5f

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
