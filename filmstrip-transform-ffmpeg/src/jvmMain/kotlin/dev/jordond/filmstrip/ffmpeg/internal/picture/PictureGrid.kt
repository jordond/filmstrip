package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.picture.heif.HeifGrid

/**
 * The filter graph that puts [grid]'s tiles together and runs the assembled picture through [filter].
 *
 * Each tile is mapped by its id, which is the id ffmpeg gives the stream it reads the tile as, and stacked in rows by
 * [HeifGrid.columns]. The whole is cropped to [HeifGrid.size] from its top left before [filter] runs. No other stream
 * the file lists, such as a thumbnail, is an input.
 */
internal fun assembledGrid(
  grid: HeifGrid,
  filter: String,
): String {
  val inputs = grid.tiles.joinToString("") { "[0:#$it]" }
  val layout =
    grid.tiles.indices.joinToString("|") { index ->
      "${offset(index % grid.columns, "w0")}_${offset(index / grid.columns, "h0")}"
    }
  val stacked = if (grid.tiles.size == 1) "" else "xstack=inputs=${grid.tiles.size}:layout=$layout,"
  return "$inputs${stacked}crop=${grid.size.width}:${grid.size.height}:0:0,$filter"
}

// [count] tiles of [side] along one axis, as an xstack layout expression.
private fun offset(
  count: Int,
  side: String,
): String = if (count == 0) "0" else List(count) { side }.joinToString("+")
