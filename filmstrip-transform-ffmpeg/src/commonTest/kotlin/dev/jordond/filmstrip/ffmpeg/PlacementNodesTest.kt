package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.ffmpeg.internal.lowering.canvasNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.drawnAt
import dev.jordond.filmstrip.ffmpeg.internal.lowering.onChromaGrid
import dev.jordond.filmstrip.ffmpeg.internal.lowering.placedNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.internal.lowering.scaledInto
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.inWholePixelsOf
import dev.jordond.filmstrip.transform.internal.geometry.placeIn
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

// How a clip's shared placement lands in ffmpeg's whole pixels. scale, crop and pad only take whole pixels, so every
// rect here is the shared one rounded by inWholePixelsOf.
class PlacementNodesTest {
  @Test
  fun `a contained frame is scaled to its rect and padded at its offset`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Contain).inWholePixelsOf(ODD_FRAME)

    placedNodes(rect, ODD_FRAME, "black").joinToString(",") { it.render() } shouldBe
      "scale=w=${rect.width}:h=${rect.height},pad=w=1366:h=768:x=${rect.left}:y=${rect.top}:color=black"
  }

  @Test
  fun `a cropped frame keeps only the part inside the frame and needs no pad`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Crop).inWholePixelsOf(ODD_FRAME)
    val graph = placedNodes(rect, ODD_FRAME, "black").joinToString(",") { it.render() }

    graph shouldBe "scale=w=${rect.width}:h=${rect.height},crop=w=1366:h=768:x=0:y=${-rect.top}"
    graph shouldNotContain "pad="
  }

  @Test
  fun `a frame that already fills the output is scaled and nothing more`() {
    val rect = placeIn(Size(1920, 1080), ODD_FRAME, Fit.Stretch).inWholePixelsOf(ODD_FRAME)

    placedNodes(rect, ODD_FRAME, "black").map { it.render() } shouldBe listOf("scale=w=1366:h=768")
  }

  @Test
  fun `the sharp half of a blurred tail is overlaid where its rect lands`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Contain).inWholePixelsOf(ODD_FRAME)

    scaledInto(rect, ODD_FRAME).map { it.render() } shouldBe listOf("scale=w=${rect.width}:h=${rect.height}")
    rect.drawnAt() shouldBe (rect.left to rect.top)
  }

  @Test
  fun `a canvas is built at the placement's own size with its content at the shared rect`() {
    val placement =
      placementOf(ODD_SOURCE, listOf(listOf(Crop(AspectRatio(4, 5), Fit.Contain), PAN)), ODD_FRAME)
    val canvas = placement.canvases.single()
    val rect = canvas.contentInCanvas.inWholePixelsOf(canvas.size)

    canvasNodes(canvas, "black@0", "yuva420p").joinToString(",") { it.render() } shouldBe
      "format=pix_fmts=yuva420p,scale=w=${rect.width}:h=${rect.height}," +
      "pad=w=${onChromaGrid(canvas.size.width)}:h=${onChromaGrid(canvas.size.height)}" +
      ":x=${onChromaGrid(rect.left)}:y=${onChromaGrid(rect.top)}:color=black@0"
  }

  // 1350 into 1920 leaves 570 to share, so the shared rect starts on an odd column. ffmpeg's pad lands an offset on
  // the 4:2:0 chroma grid, rounding an odd one down, and the graph says so rather than leaving ffmpeg to move it.
  @Test
  fun `an odd offset is pinned to the chroma grid ffmpeg pads on`() {
    val frame = Size(1920, 1080)
    val rect = placeIn(Size(1350, 1080), frame, Fit.Contain).inWholePixelsOf(frame)

    rect.left shouldBe 285
    placedNodes(rect, frame, "black").last().render() shouldBe "pad=w=1920:h=1080:x=284:y=0:color=black"
  }

  @Test
  fun `a chain with no canvas builds none`() {
    placementOf(PORTRAIT, listOf(listOf(Scale(768))), ODD_FRAME).canvases.shouldBeEmpty()
  }

  private companion object {
    val PORTRAIT = Size(1080, 1350)
    val ODD_FRAME = Size(1366, 768)
    val ODD_SOURCE = Size(1366, 767)
    val PAN = KenBurns(NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f), NormalizedRect.Full)
  }
}
