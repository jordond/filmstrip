package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size

/**
 * A rectangle on a frame, in whole pixels.
 *
 * @property x The left edge.
 * @property y The top edge.
 * @property width The width.
 * @property height The height.
 */
internal class PixelRect(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
)

/**
 * Where an overlay on an HDR grade is blended, and how its picture is laid out to line up with that part of the frame.
 *
 * The blend runs on [region] alone, cut out of the frame, and both of its inputs have to be that size. The picture is
 * padded onto a transparent canvas of [canvas] with its corner at [left] and [top], and the window of the canvas the
 * region covers is cut out wherever the picture is drawn, so the layer and the region line up pixel for pixel.
 *
 * @property region The part of the frame every drawn position of the overlay falls inside.
 * @property canvas The size of the transparent canvas the picture is padded onto.
 * @property left Where the picture's left edge sits on the canvas.
 * @property top Where the picture's top edge sits on the canvas.
 */
internal class HdrOverlayLayout(
  val region: PixelRect,
  val canvas: Size,
  val left: Int,
  val top: Int,
  private val largest: Size,
) {
  // Where the window is cut from the canvas for a picture drawn at a position on the frame. A picture drawn wholly
  // outside the region is held just clear of its edge, where the window reads nothing but the transparent canvas.
  fun windowX(drawnX: Int): Int = left - (drawnX - region.x).coerceIn(-largest.width, region.width)

  fun windowY(drawnY: Int): Int = top - (drawnY - region.y).coerceIn(-largest.height, region.height)
}

/**
 * Lays out an overlay drawn at every rectangle in [drawn] on a frame of [frame]'s size.
 *
 * The region is the smallest one holding every rectangle's part of the frame, widened to even pixels so a 4:2:0 frame
 * is cut on a chroma sample. The canvas is as small as it can be while every window stays on it.
 */
internal fun hdrOverlayLayout(
  drawn: List<PixelRect>,
  frame: Size,
): HdrOverlayLayout {
  fun span(
    starts: List<Int>,
    ends: List<Int>,
    extent: Int,
  ): Pair<Int, Int> {
    val start = starts.min().coerceIn(0, extent - 1) and EVEN
    val end = (ends.max().coerceIn(0, extent) + 1 and EVEN).coerceAtMost(extent)
    return start to (end - start).coerceAtLeast(1)
  }

  val (x, width) = span(drawn.map { it.x }, drawn.map { it.x + it.width }, frame.width)
  val (y, height) = span(drawn.map { it.y }, drawn.map { it.y + it.height }, frame.height)
  val largest = Size(drawn.maxOf { it.width }, drawn.maxOf { it.height })
  val offsetsX = drawn.map { (it.x - x).coerceIn(-largest.width, width) }
  val offsetsY = drawn.map { (it.y - y).coerceIn(-largest.height, height) }
  val left = offsetsX.max().coerceAtLeast(0)
  val top = offsetsY.max().coerceAtLeast(0)

  return HdrOverlayLayout(
    region = PixelRect(x, y, width, height),
    canvas =
      Size(
        maxOf(left + largest.width, left - offsetsX.min() + width),
        maxOf(top + largest.height, top - offsetsY.min() + height),
      ),
    left = left,
    top = top,
    largest = largest,
  )
}

// Clears the lowest bit, which rounds a non-negative pixel position down to an even one.
private const val EVEN = 1.inv()
