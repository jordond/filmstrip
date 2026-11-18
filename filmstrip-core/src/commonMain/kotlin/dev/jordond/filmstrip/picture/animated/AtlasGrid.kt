package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop

/**
 * The layout of every frame of an animated picture as the cells of one atlas, row by row from the top left.
 *
 * @property columns How many cells a row holds.
 * @property rows How many rows the atlas holds.
 * @property cell The size of one cell, which is the canvas.
 * @property cells The rectangle of the atlas each frame is written to, in frame order.
 */
@InternalFilmstripApi
public class AtlasGrid internal constructor(
  public val columns: Int,
  public val rows: Int,
  public val cell: Size,
  public val cells: List<PictureCrop>,
) {
  /**
   * The size of the whole atlas.
   */
  public val size: Size get() = Size(columns * cell.width, rows * cell.height)
}

/**
 * Lays [frameCount] frames of [canvas] out as one atlas, or returns null when no layout fits.
 *
 * Of every number of columns that holds the frames, it takes the one whose longer side is shortest, and the fewest
 * columns where two tie. It returns null when that longer side is past [maxSide], when [maxSide] is not positive, or
 * when there are no frames or the canvas has no area. A null [maxSide] puts no limit on the sides.
 */
@InternalFilmstripApi
public fun atlasGrid(
  frameCount: Int,
  canvas: Size,
  maxSide: Int?,
): AtlasGrid? {
  if (frameCount <= 0 || canvas.width <= 0 || canvas.height <= 0) return null
  var best = 0
  var bestSide = Long.MAX_VALUE
  for (columns in 1..frameCount) {
    val rows = (frameCount + columns - 1) / columns
    val side = maxOf(columns.toLong() * canvas.width, rows.toLong() * canvas.height)
    if (side < bestSide) {
      best = columns
      bestSide = side
    }
  }
  if (maxSide != null && bestSide > maxSide) return null
  if (bestSide > Int.MAX_VALUE) return null
  val rows = (frameCount + best - 1) / best
  val cells = List(frameCount) { PictureCrop((it % best) * canvas.width, (it / best) * canvas.height, canvas) }
  return AtlasGrid(best, rows, canvas, cells)
}
