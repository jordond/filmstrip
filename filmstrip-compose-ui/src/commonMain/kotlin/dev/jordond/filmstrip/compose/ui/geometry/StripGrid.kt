package dev.jordond.filmstrip.compose.ui.geometry

import dev.drewhamilton.poko.Poko
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Which source times the strip's tiles sit at, and which of them are on screen.
 *
 * Tiles are addressed in content pixels, so a tile keeps the source time it shows no matter how far the strip has
 * scrolled. [positions] feeds `rememberFilmstripFrames` and [visibleRange] feeds `FilmstripFrames.onVisibleRange` .
 *
 * ```
 * val grid = remember(scale, tileWidthPx) { StripGrid(scale, tileWidthPx) }
 * val frames = rememberFilmstripFrames(filmstrip, composition, grid.positions, heightPx)
 *
 * // Only a strip drawn by hand needs this. FilmstripStrip reports its own window.
 * LaunchedEffect(grid, frames) {
 *   snapshotFlow { listState.stripScrollPx(grid) }.collect { scrollPx ->
 *     val window = grid.visibleRange(scrollPx, viewportWidthPx)
 *     if (!window.isEmpty()) frames.onVisibleRange(window.first, window.last)
 *   }
 * }
 * ```
 *
 * @property scale The time to pixel mapping the tiles are laid out against.
 * @property tileWidthPx How wide one tile is drawn.
 */
@Poko
public class StripGrid(
  public val scale: TimelineScale,
  public val tileWidthPx: Int,
) {
  /**
   * How many tiles the strip covers.
   */
  public val count: Int =
    if (tileWidthPx <= 0 || scale.contentWidthPx <= 0f) {
      0
    } else {
      ceil(scale.contentWidthPx / tileWidthPx).toInt()
    }

  /**
   * The source time each tile shows.
   *
   * A tile is sampled at its leading edge rather than its centre, which is what makes a zoom step a superset of the one
   * before it: doubling the scale turns tile `i` into tile `2i` and asks only for the times that fall between.
   *
   * Each entry is computed when it is read rather than when the grid is built, so a grid over a long source at a close
   * zoom holds nothing until something asks it for a position.
   */
  public val positions: List<Duration> = TilePositions(scale, tileWidthPx, count)

  /**
   * How wide the tile at [index] is drawn.
   *
   * Every tile but the last is [tileWidthPx] wide. The last carries whatever content is left, so the strip ends exactly
   * where [TimelineScale.contentWidthPx] does and an overlay drawn over it lines up at the tail as well as at the head.
   */
  public fun tileWidthPxAt(index: Int): Int {
    if (index != count - 1) return tileWidthPx
    val remainder = (scale.contentWidthPx - (count - 1).toFloat() * tileWidthPx).roundToInt()
    return remainder.coerceIn(1, tileWidthPx)
  }

  /**
   * Which tiles a viewport [viewportWidthPx] wide shows when scrolled to [scrollPx].
   *
   * @param scrollPx How far the strip has scrolled, in content pixels.
   * @param viewportWidthPx How wide the strip is drawn.
   * @return The visible tiles, and an empty range for a strip with nothing in it.
   */
  public fun visibleRange(
    scrollPx: Float,
    viewportWidthPx: Float,
  ): IntRange {
    if (count == 0 || viewportWidthPx <= 0f) return IntRange.EMPTY

    val indices = 0 until count
    val first = floor(scrollPx / tileWidthPx).toInt().coerceIn(indices)
    val last = (ceil((scrollPx + viewportWidthPx) / tileWidthPx).toInt() - 1).coerceIn(indices)
    return first..maxOf(first, last)
  }

  /**
   * The tile [time] falls in.
   *
   * A time past either end answers the tile at that end rather than nothing, because a strip covers every time it has
   * tiles for. Null means the strip has no tiles at all.
   */
  public fun indexAt(time: Duration): Int? {
    if (count == 0) return null
    val index = floor(scale.xOf(time) / tileWidthPx).toInt()
    return index.coerceIn(0 until count)
  }

  /**
   * The same grid at [scale].
   */
  public fun withScale(scale: TimelineScale): StripGrid = StripGrid(scale, tileWidthPx)

  /**
   * The same grid with tiles [tileWidthPx] wide.
   */
  public fun withTileWidth(tileWidthPx: Int): StripGrid = StripGrid(scale, tileWidthPx)
}

/**
 * The leading edge times of [count] tiles [tileWidthPx] wide, computed on read.
 *
 * A grid over a long source at a close zoom runs to six figures of tiles, and the strip only ever reads the handful
 * around the viewport.
 */
private class TilePositions(
  private val scale: TimelineScale,
  private val tileWidthPx: Int,
  private val count: Int,
) : AbstractList<Duration>() {
  override val size: Int
    get() = count

  override fun get(index: Int): Duration {
    if (index !in 0 until count) {
      throw IndexOutOfBoundsException("index: $index, size: $count")
    }
    return scale.timeAt(index.toFloat() * tileWidthPx)
  }
}
