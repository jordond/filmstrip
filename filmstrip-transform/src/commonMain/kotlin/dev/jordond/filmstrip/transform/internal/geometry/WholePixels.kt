package dev.jordond.filmstrip.transform.internal.geometry

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.roundToInt

/**
 * A placement rect in whole pixels of a frame, measured from its top left.
 *
 * A backend that can only draw a frame's edge on a pixel edge draws this rect in place of the shared one.
 * [inWholePixelsOf] is the one rounding that makes it, so every such backend starts from the same pixels. A backend
 * with a coarser grid of its own, like ffmpeg's 4:2:0 offsets, clamps this rect further where it lowers it.
 *
 * @property left The column the rect starts at.
 * @property top The row the rect starts at.
 * @property right The column just past the rect's last.
 * @property bottom The row just past the rect's last.
 * @property width How many columns the rect spans, never under one when [inWholePixelsOf] made it.
 * @property height How many rows the rect spans, never under one when [inWholePixelsOf] made it.
 */
@InternalFilmstripApi
public data class PixelRect(
  public val left: Int,
  public val top: Int,
  public val right: Int,
  public val bottom: Int,
) {
  public val width: Int get() = right - left

  public val height: Int get() = bottom - top
}

/**
 * Rounds this rect to whole pixels of [frame].
 *
 * Each edge is rounded to the nearest pixel edge on its own, so the result is never more than half a pixel off this
 * rect on any side. A rect thinner than a pixel still keeps one.
 */
@InternalFilmstripApi
public fun NormalizedRect.inWholePixelsOf(frame: Size): PixelRect {
  val left = (left * frame.width).roundToInt()
  val top = (top * frame.height).roundToInt()
  return PixelRect(
    left = left,
    top = top,
    right = (right * frame.width).roundToInt().coerceAtLeast(left + 1),
    bottom = (bottom * frame.height).roundToInt().coerceAtLeast(top + 1),
  )
}
