package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.floor
import kotlin.math.max

// The 4 by 4 ordered dither matrix, row by row from the top left cell.
private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

internal const val BAYER_SIZE = 4
private const val BAYER_STEPS = 16.0
private const val HALF = 0.5
private const val CODE_MAX = 255.0
private const val BYTE_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/**
 * Every number the pixel look is made of, computed once here and consumed by all four lowerings.
 *
 * It runs on SDR encoded codes with rows counted from the top. Every position is a whole pixel worked out in integers,
 * so no backend can land a read on the other side of a pixel edge from the rest.
 */
@InternalFilmstripApi
public object PixelSignal {
  /**
   * How many cell columns a frame [width] by [height] holds for [cells] down its height, the nearest whole count to
   * square cells and never under one.
   */
  public fun columns(
    width: Int,
    height: Int,
    cells: Int,
  ): Int = max(1, (2 * cells * width + height) / (2 * height))

  /**
   * Which of [count] cells along a side of [size] pixels holds [pixel], from its centre. A centre on a cell edge
   * belongs to the cell after it.
   */
  public fun cellOf(
    pixel: Int,
    count: Int,
    size: Int,
  ): Int = ((2 * pixel + 1) * count) / (2 * size)

  /**
   * The pixel at the centre of [cell] of [count] along a side of [size] pixels. A centre on a pixel edge reads the
   * pixel after it.
   */
  public fun centreOf(
    cell: Int,
    count: Int,
    size: Int,
  ): Int = ((2 * cell + 1) * size) / (2 * count)

  /**
   * The ordered dither threshold of a cell at [cellX], [cellY], from just over `-0.5` to just under `0.5` with a mean
   * of `0.0` over every 4 by 4 block.
   */
  public fun bayer(
    cellX: Int,
    cellY: Int,
  ): Double = (BAYER[(cellY % BAYER_SIZE) * BAYER_SIZE + cellX % BAYER_SIZE] + HALF) / BAYER_STEPS - HALF

  /**
   * [code] cut to [levels] steps after an [offset] in steps, rounding a half up.
   *
   * An offset under half a step keeps a code in `0.0` to `1.0` on a step.
   */
  public fun quantise(
    code: Double,
    levels: Int,
    offset: Double,
  ): Double {
    val steps = (levels - 1).toDouble()
    return floor(code * steps + offset + HALF) / steps
  }

  /**
   * The index of the entry in [palette] nearest [rgb] by squared distance in codes, the first one on a tie.
   */
  public fun nearest(
    rgb: DoubleArray,
    palette: List<DoubleArray>,
  ): Int {
    var best = 0
    var bestDistance = Double.MAX_VALUE
    palette.forEachIndexed { index, entry ->
      val distance = (0 until CHANNELS).sumOf { (rgb[it] - entry[it]) * (rgb[it] - entry[it]) }
      if (distance < bestDistance) {
        best = index
        bestDistance = distance
      }
    }
    return best
  }

  /**
   * [palette] as R'G'B' codes, one array of three a colour.
   */
  public fun codesOf(palette: PixelPalette): List<DoubleArray> =
    palette.colors.map { color ->
      doubleArrayOf(
        (color shr RED_SHIFT and BYTE_MASK) / CODE_MAX,
        (color shr GREEN_SHIFT and BYTE_MASK) / CODE_MAX,
        (color and BYTE_MASK) / CODE_MAX,
      )
    }

  /**
   * How far a dither of `1.0` moves a colour toward a palette of [size] entries at the pattern's peak, one step of an
   * even ramp of that many.
   */
  public fun paletteSpread(size: Int): Double = 1.0 / (size - 1)

  /**
   * The colour a cell reading [rgb] ends on for [pixel], with [threshold] the cell's [bayer] value.
   *
   * A palette takes the nearest entry to the colour moved by the dither on every channel at once. Without one, levels
   * cut each channel on its own, and with neither the colour is kept.
   */
  public fun colourOf(
    rgb: DoubleArray,
    pixel: Pixel,
    threshold: Double,
  ): DoubleArray {
    val palette = pixel.palette
    return when {
      palette != null -> {
        val codes = codesOf(palette)
        val shift = pixel.dither * threshold * paletteSpread(codes.size)
        codes[nearest(DoubleArray(CHANNELS) { rgb[it] + shift }, codes)].copyOf()
      }
      pixel.levels > 0 -> {
        DoubleArray(CHANNELS) { quantise(rgb[it], pixel.levels, pixel.dither * threshold) }
      }
      else -> {
        rgb.copyOf(CHANNELS)
      }
    }
  }

  /**
   * The R'G'B' a pixel at [x], [y] of a frame [width] by [height] ends on, a pure function a test holds a backend's
   * pixels against.
   *
   * @param picture The picture with no look on it, read at a whole pixel, rows from the top.
   */
  public fun pixelAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    pixel: Pixel,
    picture: (x: Int, y: Int) -> DoubleArray,
  ): DoubleArray {
    val columns = columns(width, height, pixel.cells)
    val cellX = cellOf(x, columns, width)
    val cellY = cellOf(y, pixel.cells, height)
    val rgb = picture(centreOf(cellX, columns, width), centreOf(cellY, pixel.cells, height))
    return colourOf(rgb, pixel, bayer(cellX, cellY))
  }
}

private const val CHANNELS = 3
