package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * A `crop` node that cuts exactly [width] by [height] at [x] and [y].
 *
 * Without `exact`, `crop` rounds its size and position down to the chroma grid of a 4:2:0 or 4:2:2 frame, so an odd
 * size comes out a pixel short of what was asked for. Every crop that cuts to a size the planner decided goes through
 * here, so the frame is the size the plan says on every pixel format.
 *
 * @param width The output width, as a number or an expression.
 * @param height The output height, as a number or an expression.
 * @param x The left edge of the cut, as a number or an expression.
 * @param y The top edge of the cut, as a number or an expression.
 */
@InternalFilmstripApi
public fun exactCrop(
  width: String,
  height: String,
  x: String = "0",
  y: String = "0",
): FilterNode = FilterNode("crop", "w" to width, "h" to height, "x" to x, "y" to y, "exact" to "1")

/**
 * A `crop` node that cuts exactly [width] by [height] pixels at [x] and [y].
 *
 * @param width The output width in pixels.
 * @param height The output height in pixels.
 * @param x The left edge of the cut in pixels.
 * @param y The top edge of the cut in pixels.
 * @see exactCrop
 */
@InternalFilmstripApi
public fun exactCrop(
  width: Int,
  height: Int,
  x: Int = 0,
  y: Int = 0,
): FilterNode = exactCrop(width.toString(), height.toString(), x.toString(), y.toString())
