package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterCrop
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ChromaGrid
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FilterGraphBuilder
import dev.jordond.filmstrip.ffmpeg.internal.lowering.cropped
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class FragmentCropTest {
  @Test
  fun `a crop naming no position is centred and exact on both filters`() {
    val graph = croppedGraph(FilterCrop(width = "320", height = "180"))

    graph shouldBe
      "[0:v]split=2[rbase][rcrop];" +
      "[rcrop]crop=w=320:h=180:x=2*floor(((in_w-out_w)/2)/2):y=2*floor(((in_h-out_h)/2)/2):exact=1,hue=s=0[rfx];" +
      "[rbase][rfx]overlay=x=2*floor(((W-w)/2)/2):y=2*floor(((H-h)/2)/2):format=yuv420:alpha=straight[r]"
  }

  // overlay places on the 4:2:0 chroma grid, so a whole position moves down to the even pixel at or
  // before it, and the same number reaches both filters.
  @Test
  fun `a whole position moves down onto an even pixel on both filters`() {
    val graph = croppedGraph(FilterCrop(width = "320", height = "180", x = "5", y = "8"))

    graph shouldContain "crop=w=320:h=180:x=4:y=8:exact=1"
    graph shouldContain "overlay=x=4:y=8:format=yuv420"
  }

  @Test
  fun `a negative odd position moves down rather than towards zero`() {
    val graph = croppedGraph(FilterCrop(width = "320", height = "180", x = "-3", y = "11"))

    graph shouldContain "crop=w=320:h=180:x=-4:y=10:exact=1"
    graph shouldContain "overlay=x=-4:y=10:format=yuv420"
  }

  @Test
  fun `an expression position is wrapped so the filter snaps it once evaluated`() {
    val graph = croppedGraph(FilterCrop(width = "iw/2", height = "ih/2", x = "iw/3"))

    graph shouldContain "crop=w=iw/2:h=ih/2:x=2*floor((iw/3)/2):y=2*floor(((in_h-out_h)/2)/2):exact=1"
    graph shouldContain "overlay=x=2*floor((iw/3)/2):y=2*floor(((H-h)/2)/2):format=yuv420"
  }

  @Test
  fun `a crop that turns exactness off keeps it off`() {
    val graph = croppedGraph(FilterCrop(width = "320", height = "180", x = "2", y = "2", exact = false))

    graph shouldContain "[rcrop]crop=w=320:h=180:x=2:y=2:exact=0,hue=s=0[rfx]"
  }

  @Test
  fun `a merge runs on the prepared region and the pads its aux inputs were given`() {
    val logo = AuxInput(ImageSource.of("/logo.png"))
    val fragment =
      FilterFragment(
        chain = listOf(FilterNode("scale", "w" to "64", "h" to "64")),
        auxInputs = listOf(logo),
        merge = FilterNode("overlay"),
        post = listOf(FilterNode("format", "pix_fmts" to "yuva420p")),
      )
    val asked = mutableListOf<Pair<List<AuxInput>, String>>()
    val graph = FilterGraphBuilder()

    val crop = FilterCrop(width = "320", height = "180", x = "0", y = "0")
    graph.cropped("0:v", fragment, crop, "r", ChromaGrid.Subsampled, "yuv420p") {
      inputs,
      label,
      ->
      asked += inputs to label
      listOf("${label}aux0").onEach { graph.chain(listOf("1:v"), listOf(FilterNode("null")), it) }
    }

    asked shouldBe listOf(listOf(logo) to "r")
    graph.build("r") shouldBe
      "[0:v]split=2[rbase][rcrop];" +
      "[rcrop]crop=w=320:h=180:x=0:y=0:exact=1,scale=w=64:h=64[rpre];" +
      "[1:v]null[raux0];" +
      "[rpre][raux0]overlay=format=yuv420:alpha=straight,format=pix_fmts=yuva420p[rfx];" +
      "[rbase][rfx]overlay=x=0:y=0:format=yuv420:alpha=straight[r]"
  }

  // A base in 4:4:4, the way an odd canvas leaves its chain, has a chroma sample on every pixel, so overlay lands an
  // odd position where it was asked for and neither filter is handed a snapped one.
  // position where it was asked for and neither filter is handed a snapped one.
  @Test
  fun `a position on a full chroma base reaches both filters as written`() {
    val odd = croppedGraph(FilterCrop(width = "320", height = "180", x = "5", y = "7"), ChromaGrid.Full)
    val centred = croppedGraph(FilterCrop(width = "320", height = "180"), ChromaGrid.Full)

    odd shouldContain "crop=w=320:h=180:x=5:y=7:exact=1"
    odd shouldContain "overlay=x=5:y=7:format=auto"
    centred shouldContain "crop=w=320:h=180:x=(in_w-out_w)/2:y=(in_h-out_h)/2:exact=1"
    centred shouldContain "overlay=x=(W-w)/2:y=(H-h)/2:format=auto"
  }

  private fun croppedGraph(
    crop: FilterCrop,
    grid: ChromaGrid = ChromaGrid.Subsampled,
  ): String {
    val graph = FilterGraphBuilder()
    val fragment = FilterFragment(chain = listOf(FilterNode("hue", "s" to "0")))
    val out = graph.cropped("0:v", fragment, crop, "r", grid, "yuv420p") { _, _ -> emptyList() }
    return graph.build(out)
  }
}
