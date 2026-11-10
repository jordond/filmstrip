package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.spatial.SpatialBlurGraph
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan

/**
 * The background half of a blurred letterbox, handed on in [pixelFormat], adding the tables it reads to [sidecars].
 *
 * The frame is scaled up until it covers the output and centre-cropped to that size, then blurred by [fill]'s
 * [blurPlan] through [SpatialBlurGraph], so the edge it holds is the crop's. The blur runs on sixteen bit 4:4:4 planes
 * of the frame's own matrix, where every plane blurs at one sigma, and leaves the alpha as it came. A frame on a kept
 * [transfer] grade is blurred on its scaled SDR signal instead, so it is the blur the SDR signal would take. A
 * non-zero [Fill.Blurred.dim] darkens the result afterward, as a gain on the color channels rather than an offset, so
 * it never shifts hue.
 */
internal fun coverBlurNodes(
  outputSize: Size,
  fill: Fill.Blurred,
  pixelFormat: String,
  transfer: HdrTransfer? = null,
  sidecars: MutableList<Sidecar> = mutableListOf(),
): List<FilterNode> =
  buildList {
    val width = outputSize.width.toString()
    val height = outputSize.height.toString()
    add(FilterNode("scale", "w" to width, "h" to height, "force_original_aspect_ratio" to "increase"))
    add(FilterNode("crop", "w" to width, "h" to height))
    val plan = fill.blurPlan(outputSize)
    if (transfer != null) {
      val kept = SpatialBlurGraph.onKeptGrade(plan, outputSize, transfer, fill.backgroundGain)
      addAll(kept.chain)
      sidecars += kept.sidecars
      add(FilterNode("format", "pix_fmts" to pixelFormat))
      return@buildList
    }
    addAll(SpatialBlurGraph.boxedDown(plan, plan, outputSize, COVER_FORMAT))
    addAll(SpatialBlurGraph.blurredUp(plan, plan, outputSize, COVER_FORMAT, COLOR_PLANES))
    val gain = fill.backgroundGain
    if (gain != 1f) {
      add(FilterNode("colorchannelmixer", "rr" to gain.toString(), "gg" to gain.toString(), "bb" to gain.toString()))
    }
    add(FilterNode("format", "pix_fmts" to pixelFormat))
  }

/**
 * What a clip's tail ends on, the arguments of [trailingNodes].
 */
internal class TrailingFrames(
  val frameRate: Int,
  val pixelFormat: String,
  val slots: Long,
  val transfer: HdrTransfer? = null,
)

/**
 * The blurred tail of a clip that leaves bars: a sharp copy of the frame placed at [rect], laid over a blurred copy
 * that covers the whole [outputSize]. Names its pads from [label] and writes the frame to [label].
 *
 * `tailNodes` writes a single chain, but this needs the frame twice, once for each copy, so [pending] is flushed
 * onto a pad of its own and fanned out with [FilterGraphBuilder.split] instead. The sharp copy lands on [grid], the
 * chroma grid of the chain both copies come from. The background adds the tables it reads to [sidecars].
 */
internal fun FilterGraphBuilder.blurredTail(
  pad: String,
  pending: List<FilterNode>,
  fill: Fill.Blurred,
  rect: PixelRect,
  outputSize: Size,
  label: String,
  tail: TrailingFrames,
  grid: ChromaGrid,
  sidecars: MutableList<Sidecar>,
): String {
  val pre = "${label}pre"
  chain(listOf(pad), pending.ifEmpty { listOf(FilterNode("null")) }, pre)

  val background = "${label}bg"
  val foreground = "${label}fg"
  split(pre, listOf(background, foreground))

  val blurred = "${label}blur"
  chain(listOf(background), coverBlurNodes(outputSize, fill, tail.pixelFormat, tail.transfer, sidecars), blurred)

  val sharp = "${label}sharp"
  chain(listOf(foreground), scaledInto(rect, outputSize), sharp)

  val (x, y) = rect.drawnAt(grid)
  chain(
    listOf(blurred, sharp),
    overlayNodes(x, y, tail.pixelFormat, grid) + trailingNodes(tail.frameRate, tail.pixelFormat, tail.slots),
    label,
  )
  return label
}

/**
 * Lays the sharp foreground over the blurred background at [x], [y], for a tail ending on [pixelFormat] on [grid].
 *
 * ffmpeg's `overlay` reads its first input as the base and its second as what is drawn on top, so
 * the blurred pad has to be listed before the sharp one wherever this is chained.
 */
internal fun overlayNodes(
  x: Int,
  y: Int,
  pixelFormat: String,
  grid: ChromaGrid,
): List<FilterNode> = listOf(overlayNode(pixelFormat, grid, "x" to x.toString(), "y" to y.toString()))

private const val COVER_FORMAT = "yuva444p16le"

// The luma and both chroma planes of COVER_FORMAT, with its alpha left out.
private const val COLOR_PLANES = 7
