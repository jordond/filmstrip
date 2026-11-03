package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.container

/**
 * A `grid` item's layout, the tiles it is derived from placed in rows and the whole cropped to [size].
 *
 * @property tiles The ids of the tiles, in rows, as the grid's `dimg` reference lists them.
 * @property columns How many tiles each row holds.
 * @property rows How many rows of tiles the grid holds.
 * @property size The width and height of the assembled picture.
 */
@InternalFilmstripApi
public class HeifGrid(
  public val tiles: List<Long>,
  public val columns: Int,
  public val rows: Int,
  public val size: Size,
)

/**
 * The layout of the `grid` item [bytes] carry as their primary item, or null when they are not an AVIF or HEIC or
 * their primary item is not a `grid`.
 *
 * The tiles come from the primary item's `dimg` reference and the rows, columns and size from the grid's own data, the
 * same way [heifAlpha] reads an alpha item's grid. A file that is truncated or malformed, whose grid data is not where
 * `iloc` places it or whose tile count does not match its rows and columns carries none.
 */
@InternalFilmstripApi
public fun heifPrimaryGrid(bytes: ByteArray): HeifGrid? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { picture.primaryGrid() }
}

private fun ByteView.primaryGrid(): HeifGrid? {
  val parts = metaParts() ?: return null
  val heif = within(parts.meta.end)
  val primary = parts.primary ?: return null
  val iref = parts.iref ?: return null
  val types = parts.iinf?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  if (types[primary] != GRID) return null
  return grid(primary, iref, parts)
}

// The layout of grid item [item], its tiles from the dimg reference in [iref] and its rows, columns and size from its
// data, or null when the data is not where iloc places it or the tile count does not match.
internal fun ByteView.grid(
  item: Long,
  iref: Box,
  parts: MetaParts,
): HeifGrid? {
  val tiles = within(iref.end).tilesOf(item, iref) ?: return null
  val (start, stop) = firstExtent(parts.locations ?: return null, parts.idat, item) ?: return null
  val data = within(stop)
  val wide = data.u8(start + 1) and 1 == 1
  val rows = data.u8(start + 2) + 1
  val columns = data.u8(start + 3) + 1
  val width = if (wide) data.u32(start + 4) else data.u16(start + 4).toLong()
  val height = if (wide) data.u32(start + 8) else data.u16(start + 6).toLong()
  if (tiles.size != rows * columns || width !in 1..Int.MAX_VALUE || height !in 1..Int.MAX_VALUE) return null
  return HeifGrid(tiles.toList(), columns, rows, Size(width.toInt(), height.toInt()))
}

internal val GRID = fourcc("grid")
