package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.applyTo
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.retainedRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Exports of a canvas crop and a scale over clips of different shapes, and of a cut that is not whole, read back from
 * the written file.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleFramePlacementHardwareTest : ApplePlacementCase() {
  // A cut of the fixture that is 640 by 359.44 pixels, so the whole pixels it keeps are the planner's to say. The frame
  // written is the one the plan reports, whatever the encoder has to align it to.
  @Test
  fun `a clip crop to a frame that is not whole is written at the planned size`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(AspectRatio(CUT_WIDE, CUT_HIGH))
      val source = Size(640, 360)
      val cut = crop.retainedRect(source).applyTo(source)
      withClue("the cut has to be a whole pixel short of a fraction for this to tell") {
        cut shouldBe Size(source.width, source.height - 1)
      }

      val (path, plan) =
        exported(
          compositionOf {
            clip(MediaSource.of(fixture(LANDSCAPE))) {
              trim(0.seconds, 1.seconds)
              effects { add(crop) }
            }
          },
          temporaryPath("odd-cut"),
          ExportSpec(bitrate = COLOR_READ_BITRATE),
        )

      val written = frameOf(path)
      withClue("planned ${plan.output.size}, cut $cut, written ${written.width}x${written.height}") {
        Size(written.width, written.height) shouldBe plan.output.size
      }
      remove(path)
    }

  // The canvas's aspect and the frame's height make the frame, whichever of two clips of different shapes is first. The
  // frame is the plan's, the same for both orders, and its edges hold what the plan places there: a bar where the
  // content stays clear of the edge and the picture where it reaches it.
  @Test
  fun `a canvas crop and a scale write the frame of the aspect whichever clip comes first`() =
    runTest(timeout = TIMEOUT) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        val plans = mutableListOf<Size>()
        for (order in listOf(listOf(LANDSCAPE, PORTRAIT), listOf(PORTRAIT, LANDSCAPE))) {
          val stages = listOf(Crop(aspect, Fit.Contain), Scale(FRAME_HEIGHT))
          val (path, plan) =
            exported(
              compositionOf {
                order.forEach { name -> clip(MediaSource.of(fixture(name))) { trim(0.seconds, 1.seconds) } }
                effects { stages.forEach { add(it) } }
                fill(Fill.Solid(SLATE.toArgb()))
              },
              temporaryPath("canvas-frame"),
              ExportSpec(bitrate = COLOR_READ_BITRATE),
            )
          val first = (filmstrip.probe(MediaSource.of(fixture(order.first()))) as ProbeResult.Success).info.video!!
          val content = placementOf(first.displaySize, listOf(emptyList(), stages), plan.output.size).drawnRect

          val written = frameOf(path)
          withClue("$aspect from $order: planned ${plan.output.size}, written ${written.width}x${written.height}") {
            Size(written.width, written.height) shouldBe plan.output.size
          }
          assertEdges("$aspect from $order", written, content)
          plans += plan.output.size
          remove(path)
        }
        withClue("$aspect over both orders planned $plans") { plans.toSet().size shouldBe 1 }
      }
    }

  // The first row, last row, first column and last column of the frame, each a bar where the content stays two pixels
  // or more clear of it and the picture where the content reaches it. A column or row of padding the frame should not
  // have, or one it lost, flips one of the four.
  private fun assertEdges(
    name: String,
    frame: FrameProbe,
    content: NormalizedRect,
  ) {
    val halfX = 0.5f / frame.width
    val halfY = 0.5f / frame.height
    val edges =
      listOf(
        Triple("first row", content.top * frame.height, frame.at(0.5f, halfY)),
        Triple("last row", (1 - content.bottom) * frame.height, frame.at(0.5f, 1 - halfY)),
        Triple("first column", content.left * frame.width, frame.at(halfX, 0.5f)),
        Triple("last column", (1 - content.right) * frame.width, frame.at(1 - halfX, 0.5f)),
      )
    for ((edge, barPixels, read) in edges) {
      withClue("$name, $edge read $read with a bar of $barPixels pixels") {
        when {
          barPixels >= BAR_PIXELS -> isSlate(read).shouldBeTrue()
          barPixels < REACHED_PIXELS -> isSlate(read).shouldBeFalse()
        }
      }
    }
  }

  private companion object {
    // An aspect a hair wider than the fixture's, which keeps its whole width and all but a pixel of its height.
    const val CUT_WIDE = 641
    const val CUT_HIGH = 360
  }
}
