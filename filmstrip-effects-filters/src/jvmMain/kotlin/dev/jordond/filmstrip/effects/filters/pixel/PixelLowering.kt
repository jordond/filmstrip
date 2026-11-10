package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.AHEAD_OF_THE_CLIP
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.geometry.Size

/**
 * The filter graph that draws a [Pixel] on ffmpeg, on [frame], with every figure read from [PixelSignal].
 *
 * The picture runs on straight `gbrap` through [pixelCells], and comes back to `yuva420p` with alpha moved with the
 * cells. 4:2:0 cannot hold a cell edge in chroma finer than its samples, which is ffmpeg's frame format and not the
 * look.
 */
internal fun pixelFragment(
  pixel: Pixel,
  frame: Size,
): FilterFragment =
  FilterFragment(
    branches =
      listOf(FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening() + RgbRoundTrip.toRgb(), listOf(RGB_PAD))) +
        pixelCells(pixel, frame, RGB_PAD, CELLS_PAD) +
        FilterBranch(listOf(CELLS_PAD), RgbRoundTrip.toCodes(), listOf(OUT_PAD)),
  )

/**
 * The branches that draw [PixelSignal.pixelAt] on a straight `gbrap` frame of [frame] at the pad [reads], each plane
 * in 8 bit codes, and write it to the pad [writes].
 *
 * [pixelGather] picks out each cell's centre pixel, a `geq` the size of the grid cuts each cell's colour with `X` and
 * `Y` as the cell, and a `remap` lays every cell back over the pixels it covers, found the way [PixelSignal.cellOf]
 * finds them. A palette is searched one entry after another with a strict less than, so the first entry wins a tie,
 * and each plane keeps its own channel of the winner.
 */
internal fun pixelCells(
  pixel: Pixel,
  frame: Size,
  reads: String,
  writes: String,
): List<FilterBranch> {
  val grid = pixelGrid(pixel, frame)
  val threshold = "st($THRESHOLD,${bayerExpression()});"
  val cut =
    FilterNode(
      "geq",
      "r" to threshold + colourExpression(pixel, RED),
      "g" to threshold + colourExpression(pixel, GREEN),
      "b" to threshold + colourExpression(pixel, BLUE),
      "a" to "alpha(X,Y)",
    )
  return pixelGather(pixel, frame, reads, GATHERED_PAD) +
    listOf(
      map(frame, "floor((2*X+1)*${grid.width}/${2 * frame.width})", SPREAD_X_PAD),
      map(frame, "floor((2*Y+1)*${grid.height}/${2 * frame.height})", SPREAD_Y_PAD),
      FilterBranch(listOf(GATHERED_PAD), listOf(cut), listOf(CUT_PAD)),
      FilterBranch(listOf(CUT_PAD, SPREAD_X_PAD, SPREAD_Y_PAD), listOf(REMAP, format(RGB_FORMAT)), listOf(writes)),
    )
}

/**
 * The branches that read the pixel at the centre of every cell of a `gbrap` frame of [frame] at the pad [reads] into a
 * [pixelGrid] sized frame at the pad [writes], one pixel a cell, found the way [PixelSignal.centreOf] finds it.
 */
internal fun pixelGather(
  pixel: Pixel,
  frame: Size,
  reads: String,
  writes: String,
): List<FilterBranch> {
  val grid = pixelGrid(pixel, frame)
  return listOf(
    map(grid, "floor((2*X+1)*${frame.width}/${2 * grid.width})", GATHER_X_PAD),
    map(grid, "floor((2*Y+1)*${frame.height}/${2 * grid.height})", GATHER_Y_PAD),
    FilterBranch(listOf(reads, GATHER_X_PAD, GATHER_Y_PAD), listOf(REMAP, format(RGB_FORMAT)), listOf(writes)),
  )
}

/**
 * The cells across and down that a [Pixel] cuts a [frame] into, from [PixelSignal.columns] and [Pixel.cells].
 */
@OptIn(InternalFilmstripApi::class)
internal fun pixelGrid(
  pixel: Pixel,
  frame: Size,
): Size = Size(PixelSignal.columns(frame.width, frame.height, pixel.cells), pixel.cells)

// A map of one frame, drawn once by its geq ahead of the clip, which remap holds for every frame after it.
private fun map(
  size: Size,
  expression: String,
  writes: String,
): FilterBranch =
  FilterBranch(
    emptyList(),
    listOf(
      FilterNode("color", "c" to "black", "s" to "${size.width}x${size.height}", "r" to "1", "d" to "1"),
      format(MAP_FORMAT),
      FilterNode("geq", "lum" to expression),
      AHEAD_OF_THE_CLIP,
    ),
    listOf(writes),
  )

private fun format(pixelFormat: String): FilterNode = FilterNode("format", "pix_fmts" to pixelFormat)

// The cell's threshold, picked out of PixelSignal.bayer's 4 by 4 table by the cell's place in its block.
@OptIn(InternalFilmstripApi::class)
private fun bayerExpression(): String {
  val index = "mod(Y,$BAYER_SIZE)*$BAYER_SIZE+mod(X,$BAYER_SIZE)"
  val thresholds = (0 until BAYER_SIZE * BAYER_SIZE).map { PixelSignal.bayer(it % BAYER_SIZE, it / BAYER_SIZE) }
  return thresholds.dropLast(1).foldIndexed("") { at, open, threshold ->
    "${open}if(eq($index,$at),$threshold,"
  } + thresholds.last() + ")".repeat(thresholds.size - 1)
}

@OptIn(InternalFilmstripApi::class)
private fun colourExpression(
  pixel: Pixel,
  channel: Int,
): String {
  val read = { plane: Int -> "${PLANE_READS[plane]}(X,Y)" }
  val palette = pixel.palette
  val dither = pixel.dither.toDouble()
  return when {
    palette != null -> {
      val entries = PixelSignal.codesOf(palette).map { codes -> codes.map { it * FULL_CODE } }
      val shift = "$dither*ld($THRESHOLD)*${PixelSignal.paletteSpread(entries.size) * FULL_CODE}"
      val moved = (0 until CHANNELS).joinToString("") { "st(${MOVED + it},${read(it)}+$shift);" }
      val distance = { entry: List<Double> ->
        (0 until CHANNELS).joinToString("+") { "pow(ld(${MOVED + it})-${entry[it]},2)" }
      }
      val search =
        entries.drop(1).joinToString("") { entry ->
          "if(lt(${distance(
            entry,
          )},ld($BEST_DISTANCE)),st($BEST_DISTANCE,${distance(entry)})+st($BEST,${entry[channel]}),0);"
        }
      moved + "st($BEST_DISTANCE,${distance(entries.first())});st($BEST,${entries.first()[channel]});" + search +
        "floor(ld($BEST)+0.5)"
    }
    pixel.levels > 0 -> {
      val steps = pixel.levels - 1
      "floor(floor(${read(channel)}/$FULL_CODE*$steps+$dither*ld($THRESHOLD)+0.5)/$steps*$FULL_CODE+0.5)"
    }
    else -> {
      read(channel)
    }
  }
}

private const val FULL_CODE = 255.0
private const val CHANNELS = 3
private const val RED = 0
private const val GREEN = 1
private const val BLUE = 2
private val PLANE_READS = listOf("r", "g", "b")

private const val RGB_FORMAT = "gbrap"
private const val MAP_FORMAT = "gray16le"
private val REMAP = FilterNode("remap")

private const val RGB_PAD = "rgb"
private const val CELLS_PAD = "cells"
private const val OUT_PAD = "out"
private const val GATHERED_PAD = "gathered"
private const val CUT_PAD = "cut"
private const val GATHER_X_PAD = "gatherX"
private const val GATHER_Y_PAD = "gatherY"
private const val SPREAD_X_PAD = "spreadX"
private const val SPREAD_Y_PAD = "spreadY"

// geq's registers.
private const val THRESHOLD = 0
private const val BEST = 1
private const val BEST_DISTANCE = 2
private const val MOVED = 3
