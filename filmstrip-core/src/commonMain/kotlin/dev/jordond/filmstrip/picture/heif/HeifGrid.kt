package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
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
 * @property tileSizes The width and height of each tile, in the order of [tiles], as its `ispe` and any `clap` give it.
 */
@InternalFilmstripApi
public class HeifGrid(
  public val tiles: List<Long>,
  public val columns: Int,
  public val rows: Int,
  public val size: Size,
  public val tileSizes: List<Size>,
) {
  /**
   * The bytes putting this grid together holds at its peak, or null when that count does not fit in a [Long].
   *
   * Every tile is counted at its own size and the assembled picture at [size], each at [ASSEMBLY_TEXEL_BYTES] a texel,
   * held together.
   */
  public val peakBytes: Long?
    get() = assemblyPeakBytes(size, tileSizes)
}

/**
 * What reading a `grid` item comes to, as [heifPrimaryGrid] gives it.
 *
 * A [Read] result holds a layout every backend can assemble under [PICTURE_PEAK_CEILING]. A [Refused] one holds the
 * sentence a backend reports instead, and nothing is assembled.
 */
@InternalFilmstripApi
public sealed interface HeifGridRead {
  /**
   * The grid's layout, within every bound the read checks.
   *
   * @property grid The layout.
   */
  @InternalFilmstripApi
  public class Read(
    public val grid: HeifGrid,
  ) : HeifGridRead

  /**
   * A grid that is well formed but past a bound, [GRID_TOO_LARGE_REASON].
   *
   * @property reason Why, in a sentence a person reads.
   */
  @InternalFilmstripApi
  public class Refused(
    public val reason: String,
  ) : HeifGridRead
}

/**
 * The layout of the `grid` item [bytes] carry as their primary item, or null when they are not an AVIF or HEIC or
 * their primary item is not a `grid`.
 *
 * The tiles come from the primary item's `dimg` reference, each sized by its own properties, and the rows, columns and
 * size from the grid's own data, the same way [heifAlpha] reads an alpha item's grid. A file that is truncated or
 * malformed, whose grid data is not where `iloc` places it, whose tile count does not match its rows and columns or
 * that has a tile with no size carries none. A well formed grid whose [HeifGrid.peakBytes] pass [PICTURE_PEAK_CEILING]
 * is refused.
 */
@InternalFilmstripApi
public fun heifPrimaryGrid(bytes: ByteArray): HeifGridRead? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { picture.primaryGrid() }
}

private fun ByteView.primaryGrid(): HeifGridRead? {
  val parts = metaParts() ?: return null
  val heif = within(parts.meta.end)
  val primary = parts.primary ?: return null
  val iref = parts.iref ?: return null
  val types = parts.iinf?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  if (types[primary] != GRID) return null
  return grid(primary, iref, parts)
}

// The layout of grid item [item], its tiles from the dimg reference in [iref] and its rows, columns and size from its
// data, refused past the bound heifPrimaryGrid names, or null when it is malformed the ways heifPrimaryGrid names.
internal fun ByteView.grid(
  item: Long,
  iref: Box,
  parts: MetaParts,
): HeifGridRead? {
  val heif = within(parts.meta.end)
  val tiles = heif.within(iref.end).tilesOf(item, iref) ?: return null
  val (start, stop) = firstExtent(parts.locations ?: return null, parts.idat, item) ?: return null
  val data = within(stop)
  val wide = data.u8(start + 1) and 1 == 1
  val rows = data.u8(start + 2) + 1
  val columns = data.u8(start + 3) + 1
  val width = if (wide) data.u32(start + 4) else data.u16(start + 4).toLong()
  val height = if (wide) data.u32(start + 8) else data.u16(start + 6).toLong()
  if (tiles.size != rows * columns || width !in 1..Int.MAX_VALUE || height !in 1..Int.MAX_VALUE) return null
  val listings = heif.heifListings(parts.ipmas, tiles.toSet(), parts.properties)
  val sizes = tiles.map { tile -> listings[tile]?.let { heif.heifExtentOf(it) } ?: return null }
  val grid = HeifGrid(tiles.toList(), columns, rows, Size(width.toInt(), height.toInt()), sizes)
  val peak = grid.peakBytes
  if (peak == null || peak > PICTURE_PEAK_CEILING) return HeifGridRead.Refused(GRID_TOO_LARGE_REASON)
  return HeifGridRead.Read(grid)
}

/**
 * The reason a grid whose [HeifGrid.peakBytes] pass [PICTURE_PEAK_CEILING] is refused.
 *
 * It names the ceiling in bytes, the same one every other overlay picture is held under.
 */
@InternalFilmstripApi
public const val GRID_TOO_LARGE_REASON: String =
  "The overlay image is a grid whose tiles and picture come to more than $PICTURE_PEAK_CEILING bytes to put " +
    "together, the most one picture may hold, so this overlay picture cannot be drawn."

internal val GRID = fourcc("grid")
