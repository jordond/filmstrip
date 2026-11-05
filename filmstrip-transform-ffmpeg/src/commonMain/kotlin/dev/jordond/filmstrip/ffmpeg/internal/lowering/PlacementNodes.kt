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
 * Where the part [scaledInto] keeps sits in the frame, on the chroma grid [onChromaGrid] clamps to.
 */
internal fun PixelRect.drawnAt(): Pair<Int, Int> =
  onChromaGrid(left.coerceAtLeast(0)) to onChromaGrid(top.coerceAtLeast(0))

/**
 * This figure rounded down to the 4:2:0 chroma grid, the nearest even pixel at or below it.
 *
 * ffmpeg's `pad` and `overlay` land a frame's offset and `pad` its size on that grid, rounding an odd figure down,
 * and every format this lowering writes is 4:2:0. That is ffmpeg's limit, so the rect is clamped here, where the graph
 * says where the frame really lands, rather than ffmpeg moving it a pixel without saying so.
 */
internal fun onChromaGrid(pixels: Int): Int = pixels - pixels % 2

/**
 * Places the frame at [rect] in a frame of [frame], with [padColor] wherever it does not reach.
 */
internal fun placedNodes(
  rect: PixelRect,
  frame: Size,
  padColor: String,
): List<FilterNode> =
  buildList {
    addAll(scaledInto(rect, frame))
    val (x, y) = rect.drawnAt()
    val reachesEveryEdge =
      x == 0 && y == 0 && rect.right >= frame.width && rect.bottom >= frame.height
    if (!reachesEveryEdge) {
      add(
        FilterNode(
          "pad",
          "w" to onChromaGrid(frame.width).toString(),
          "h" to onChromaGrid(frame.height).toString(),
          "x" to x.toString(),
          "y" to y.toString(),
          "color" to padColor,
        ),
      )
    }
  }

/**
 * Builds [canvas] out of the frame entering the crop that makes it, in [pixelFormat], bars in [padColor].
 *
 * The canvas is [CanvasPlacement.size], the frame the planner measures every later effect against, and the frame lands
 * at [CanvasPlacement.contentInCanvas] rounded by the shared [inWholePixelsOf]. The frame is converted to
 * [pixelFormat] first, so a transparent [padColor] has an alpha plane to land in and the canvas sits on the chroma grid
 * [onChromaGrid] clamps to.
 */
internal fun canvasNodes(
  canvas: CanvasPlacement,
  padColor: String,
  pixelFormat: String,
): List<FilterNode> =
  listOf(FilterNode("format", "pix_fmts" to pixelFormat)) +
    placedNodes(canvas.contentInCanvas.inWholePixelsOf(canvas.size), canvas.size, padColor)
