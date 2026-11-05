package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ChromaGrid
import dev.jordond.filmstrip.ffmpeg.internal.lowering.canvasNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.drawnAt
import dev.jordond.filmstrip.ffmpeg.internal.lowering.fullChromaFormat
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
import io.kotest.assertions.withClue
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

    placedNodes(rect, ODD_FRAME, "black", ChromaGrid.Subsampled).joinToString(",") { it.render() } shouldBe
      "scale=w=${rect.width}:h=${rect.height},pad=w=1366:h=768:x=${rect.left}:y=${rect.top}:color=black"
  }

  @Test
  fun `a cropped frame keeps only the part inside the frame and needs no pad`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Crop).inWholePixelsOf(ODD_FRAME)
    val graph = placedNodes(rect, ODD_FRAME, "black", ChromaGrid.Subsampled).joinToString(",") { it.render() }

    graph shouldBe "scale=w=${rect.width}:h=${rect.height},crop=w=1366:h=768:x=0:y=${-rect.top}"
    graph shouldNotContain "pad="
  }

  @Test
  fun `a frame that already fills the output is scaled and nothing more`() {
    val rect = placeIn(Size(1920, 1080), ODD_FRAME, Fit.Stretch).inWholePixelsOf(ODD_FRAME)

    placedNodes(rect, ODD_FRAME, "black", ChromaGrid.Subsampled).map { it.render() } shouldBe
      listOf("scale=w=1366:h=768")
  }

  @Test
  fun `the sharp half of a blurred tail is overlaid where its rect lands`() {
    val rect = placeIn(PORTRAIT, ODD_FRAME, Fit.Contain).inWholePixelsOf(ODD_FRAME)

    scaledInto(rect, ODD_FRAME).map { it.render() } shouldBe listOf("scale=w=${rect.width}:h=${rect.height}")
    rect.drawnAt(ChromaGrid.Subsampled) shouldBe (rect.left to rect.top)
  }

  // An odd canvas runs in 4:4:4, where pad takes an odd height and offset as given, so the 767 rows the planner
  // measures every later effect against are the rows the graph builds.
  @Test
  fun `a canvas of odd height is built at the placement's own size with its content at the shared rect`() {
    val placement =
      placementOf(ODD_SOURCE, listOf(listOf(Crop(AspectRatio(4, 5), Fit.Contain), PAN)), ODD_FRAME)
    val canvas = placement.canvases.single()
    val rect = canvas.contentInCanvas.inWholePixelsOf(canvas.size)

    canvas.size.height shouldBe ODD_SOURCE.height
    canvasNodes(canvas, "black@0", "yuva420p").joinToString(",") { it.render() } shouldBe
      "format=pix_fmts=yuva444p,scale=w=${rect.width}:h=${rect.height}," +
      "pad=w=${canvas.size.width}:h=${canvas.size.height}:x=${rect.left}:y=${rect.top}:color=black@0"
  }

  // A canvas whose size and offset are all even is already on the 4:2:0 grid, so it never leaves the format it is
  // handed and its chroma goes through untouched.
  @Test
  fun `a canvas of even size and offset stays in its own format`() {
    val placement = placementOf(EVEN_SOURCE, listOf(listOf(Crop(AspectRatio.Square, Fit.Contain), PAN)), ODD_FRAME)
    val canvas = placement.canvases.single()
    val rect = canvas.contentInCanvas.inWholePixelsOf(canvas.size)
    val figures = listOf(canvas.size.width, canvas.size.height, rect.left, rect.top)
    withClue("every figure of $figures has to be even for this to tell") { figures.all { it % 2 == 0 } shouldBe true }

    val graph = canvasNodes(canvas, "black@0", "yuva420p").joinToString(",") { it.render() }

    graph shouldBe
      "format=pix_fmts=yuva420p,scale=w=${rect.width}:h=${rect.height}," +
      "pad=w=${canvas.size.width}:h=${canvas.size.height}:x=${rect.left}:y=${rect.top}:color=black@0"
    graph shouldNotContain "444"
  }

  // A full chroma chain takes an odd figure as given, so the frame lands on the shared rect's own column.
  @Test
  fun `an odd offset on a full chroma chain is padded where the shared rect puts it`() {
    val frame = Size(1920, 1080)
    val rect = placeIn(Size(1350, 1080), frame, Fit.Contain).inWholePixelsOf(frame)

    rect.left shouldBe 285
    rect.drawnAt(ChromaGrid.Full) shouldBe (285 to 0)
    placedNodes(rect, frame, "black", ChromaGrid.Full).last().render() shouldBe
      "pad=w=1920:h=1080:x=285:y=0:color=black"
  }

  @Test
  fun `a chain's grid follows the last format it is written in`() {
    fun format(pixelFormat: String) = FilterNode("format", "pix_fmts" to pixelFormat)
    val hue = FilterNode("hue", "s" to "0")

    ChromaGrid.Subsampled.after(listOf(format("yuv444p10le"), hue)) shouldBe ChromaGrid.Full
    ChromaGrid.Subsampled.after(listOf(format("gbrap"))) shouldBe ChromaGrid.Full
    ChromaGrid.Full.after(listOf(format("yuv444p"), format("yuv420p10le"))) shouldBe ChromaGrid.Subsampled
    ChromaGrid.Full.after(listOf(format("yuva444p|yuv420p"))) shouldBe ChromaGrid.Subsampled
    ChromaGrid.Full.after(listOf(format("nv12"))) shouldBe ChromaGrid.Subsampled
    ChromaGrid.Full.after(listOf(hue)) shouldBe ChromaGrid.Full
    ChromaGrid.Subsampled.after(listOf(hue)) shouldBe ChromaGrid.Subsampled
  }

  @Test
  fun `a canvas keeps the depth and alpha of the format it is handed`() {
    fullChromaFormat("yuv420p") shouldBe "yuv444p"
    fullChromaFormat("yuva420p") shouldBe "yuva444p"
    fullChromaFormat("p010le") shouldBe "yuv444p10le"
    fullChromaFormat("yuv420p10le") shouldBe "yuv444p10le"
    fullChromaFormat("yuva420p10le") shouldBe "yuva444p10le"
  }

  // 1350 into 1920 leaves 570 to share, so the shared rect starts on an odd column. ffmpeg's pad lands an offset on
  // the 4:2:0 chroma grid, rounding an odd one down, and the graph says so rather than leaving ffmpeg to move it.
  @Test
  fun `an odd offset is pinned to the chroma grid ffmpeg pads on`() {
    val frame = Size(1920, 1080)
    val rect = placeIn(Size(1350, 1080), frame, Fit.Contain).inWholePixelsOf(frame)

    rect.left shouldBe 285
    placedNodes(rect, frame, "black", ChromaGrid.Subsampled).last().render() shouldBe
      "pad=w=1920:h=1080:x=284:y=0:color=black"
  }

  @Test
  fun `a chain with no canvas builds none`() {
    placementOf(PORTRAIT, listOf(listOf(Scale(768))), ODD_FRAME).canvases.shouldBeEmpty()
  }

  private companion object {
    val PORTRAIT = Size(1080, 1350)
    val ODD_FRAME = Size(1366, 768)
    val ODD_SOURCE = Size(1366, 767)
    val EVEN_SOURCE = Size(1920, 1080)
    val PAN = KenBurns(NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f), NormalizedRect.Full)
  }
}
