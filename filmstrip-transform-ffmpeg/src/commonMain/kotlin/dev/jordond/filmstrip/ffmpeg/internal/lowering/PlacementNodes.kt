package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.CanvasPlacement
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.geometry.inWholePixelsOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.drawnRect

/**
 * Where this clip's frame is drawn in [frame] at the size stage, the clip's [drawnRect] in whole pixels.
 *
 * `scale`, `crop` and `pad` only take whole pixels, which is ffmpeg's limit. The rect is rounded by the shared
 * [inWholePixelsOf], so it lands on the same pixels every whole pixel backend draws.
 */
internal fun ResolvedClip.drawnIn(frame: Size): PixelRect = drawnRect.inWholePixelsOf(frame)

/**
 * Scales the frame to [rect] and cuts away whatever of it lands outside [frame], leaving only the part that is drawn.
 *
 * What comes out sits at [drawnAt] in [frame]. The caller either pads around it or overlays it there.
 */
internal fun scaledInto(
  rect: PixelRect,
  frame: Size,
): List<FilterNode> =
  buildList {
    add(FilterNode("scale", "w" to rect.width.toString(), "h" to rect.height.toString()))
    val cropX = (-rect.left).coerceAtLeast(0)
    val cropY = (-rect.top).coerceAtLeast(0)
    val keptWidth = minOf(rect.right, frame.width) - maxOf(rect.left, 0)
    val keptHeight = minOf(rect.bottom, frame.height) - maxOf(rect.top, 0)
    if (keptWidth != rect.width || keptHeight != rect.height) {
      add(
        FilterNode(
          "crop",
          "w" to keptWidth.toString(),
          "h" to keptHeight.toString(),
          "x" to cropX.toString(),
          "y" to cropY.toString(),
        ),
      )
    }
  }

/**
 * Where the part [scaledInto] keeps sits in the frame, on the chroma [grid] of the chain it is placed on.
 */
internal fun PixelRect.drawnAt(grid: ChromaGrid): Pair<Int, Int> =
  grid.snap(left.coerceAtLeast(0)) to grid.snap(top.coerceAtLeast(0))

/**
 * The grid ffmpeg's `pad` and `overlay` land a frame's offset and `pad` its size on, set by the chroma layout of the
 * format the chain is in at that point.
 *
 * On a [Subsampled] chain they round an odd figure down to the even pixel at or below it. That is ffmpeg's limit, so
 * the rect is clamped here, where the graph says where the frame really lands, rather than ffmpeg moving it a pixel
 * without saying so. A [Full] chain has a chroma sample for every pixel and takes every figure as given.
 */
internal enum class ChromaGrid {
  Subsampled,
  Full,
  ;

  /**
   * [pixels] on this grid. A [Subsampled] chain rounds it down to the even pixel at or below it, a [Full] one keeps
   * it as it is.
   */
  fun snap(pixels: Int): Int = if (this == Subsampled) pixels - pixels % 2 else pixels

  /**
   * The grid of the chain once [nodes] have run on it, read from the last `format` among them.
   *
   * Nodes that name no format leave the grid as it was. ffmpeg's negotiation only picks a subsampled format for them
   * when no full chroma one is on offer, and `overlay` keeps the layout of the frame it draws on, so a [Full] chain
   * stays full. A format this does not recognise reads as [Subsampled], which can only snap a figure that did not
   * need it.
   */
  fun after(nodes: List<FilterNode>): ChromaGrid {
    val format =
      nodes
        .lastOrNull { it.name == "format" }
        ?.arguments
        ?.firstOrNull { it.key == "pix_fmts" }
        ?.value ?: return this
    return if (format.split('|').all(FULL_CHROMA::matches)) Full else Subsampled
  }
}

// Every format whose chroma has a sample for every pixel: 4:4:4 Y'CbCr, planar and packed RGB, and gray.
private val FULL_CHROMA = Regex("(yuva?444p|gbra?p|rgb|bgr|argb|abgr|gray).*")

/**
 * Places the frame at [rect] in a frame of [frame], with [padColor] wherever it does not reach, its size and offset
 * on [grid].
 */
internal fun placedNodes(
  rect: PixelRect,
  frame: Size,
  padColor: String,
  grid: ChromaGrid,
): List<FilterNode> =
  buildList {
    addAll(scaledInto(rect, frame))
    val (x, y) = rect.drawnAt(grid)
    val reachesEveryEdge =
      x == 0 && y == 0 && rect.right >= frame.width && rect.bottom >= frame.height
    if (!reachesEveryEdge) {
      add(
        FilterNode(
          "pad",
          "w" to grid.snap(frame.width).toString(),
          "h" to grid.snap(frame.height).toString(),
          "x" to x.toString(),
          "y" to y.toString(),
          "color" to padColor,
        ),
      )
    }
  }

/**
 * Builds [canvas] out of the frame entering the crop that makes it, bars in [padColor].
 *
 * The canvas is [CanvasPlacement.size], the frame the planner measures every later effect against, and the frame lands
 * at [CanvasPlacement.contentInCanvas] rounded by the shared [inWholePixelsOf]. Where that size and offset are all
 * even, the frame is converted to [pixelFormat] and every figure is already on its chroma grid. Where any of them is
 * odd, it is converted to the 4:4:4 format at [pixelFormat]'s own depth and alpha instead, so `pad` takes the odd
 * figure as given. Either way the canvas is built at the shared size and position exactly. The first node is the
 * `format` [ChromaGrid.after] reads the chain's grid from.
 */
internal fun canvasNodes(
  canvas: CanvasPlacement,
  padColor: String,
  pixelFormat: String,
): List<FilterNode> {
  val rect = canvas.contentInCanvas.inWholePixelsOf(canvas.size)
  val (x, y) = rect.drawnAt(ChromaGrid.Full)
  val onSubsampledGrid = listOf(canvas.size.width, canvas.size.height, x, y).all { it % 2 == 0 }
  val grid = if (onSubsampledGrid) ChromaGrid.Subsampled else ChromaGrid.Full
  val format = if (onSubsampledGrid) pixelFormat else fullChromaFormat(pixelFormat)
  return listOf(FilterNode("format", "pix_fmts" to format)) + placedNodes(rect, canvas.size, padColor, grid)
}

/**
 * [canvas] with clear bars for a clip under a blurred fill, ahead of [overBlack].
 *
 * The frame is brought to limited range first, which is the range [overBlack]'s black is written in.
 */
internal fun clearCanvasNodes(
  canvas: CanvasPlacement,
  pixelFormat: String,
): List<FilterNode> =
  listOf(FilterNode("scale", "out_range" to "limited")) +
    canvasNodes(canvas, transparentColor(), alphaPixelFormat(pixelFormat))

/**
 * The format with [pixelFormat]'s bit depth and alpha but a chroma sample for every pixel.
 *
 * Fails loudly on a format this has no equivalent for, the way [alphaPixelFormat] does, so a new output format shows
 * up as a build failure rather than a canvas a row short.
 */
internal fun fullChromaFormat(pixelFormat: String): String =
  when (pixelFormat) {
    "yuv420p" -> "yuv444p"
    "yuva420p" -> "yuva444p"
    "yuv420p10le", "p010le" -> "yuv444p10le"
    "yuva420p10le" -> "yuva444p10le"
    else -> error("No 4:4:4 pixel format known for $pixelFormat.")
  }
