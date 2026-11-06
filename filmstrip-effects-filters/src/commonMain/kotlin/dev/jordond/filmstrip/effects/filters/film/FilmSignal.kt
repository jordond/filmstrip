package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import dev.jordond.filmstrip.effects.filters.internal.smoothstep
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Strengths at 1, and the shapes that stay fixed at every strength.
private const val GRAIN_STRENGTH = 0.07
private const val HALATION_GAIN = 0.35
internal const val HALATION_LOW = 0.7
internal const val HALATION_HIGH = 0.95
internal const val HALATION_SIGMA = 0.012
internal const val HALATION_TINT_RED = 1.0
internal const val HALATION_TINT_GREEN = 0.25
internal const val HALATION_TINT_BLUE = 0.08

// The grain's clock. A frame's time comes truncated to a microsecond, so the slack, a few microseconds of a tick, holds
// a frame on an exact multiple of the rate in its own tick.
internal const val GRAIN_TICK_RATE = 24.0
internal const val GRAIN_TICK_SLACK = 1e-4

// The grain's raster is 1920 by 1080 cells. Its fit on a frame, and how a pixel lands in a cell, stays in integers. A
// cell's edge is frame pixels over GRAIN_CELL_SCALE, which is 1080 * 1920 / 120, with 120 taken out of the raster's
// two sides so the product holds in 32 bits at any frame size.
internal const val GRAIN_CELLS_ACROSS = 1920
internal const val GRAIN_CELL_SCALE = 17_280
internal const val GRAIN_FIT_X = 9
internal const val GRAIN_FIT_Y = 16

// One random stream for each of the two draws a grain value sums.
internal const val PASS_GRAIN_FIRST = 1
internal const val PASS_GRAIN_SECOND = 2
internal const val GRAIN_SEED = 1895

// grainWeight's shape, a parabola that peaks in the midtones over a floor that keeps grain in the shadows and
// highlights.
internal const val GRAIN_WEIGHT_FLOOR = 0.25
internal const val GRAIN_WEIGHT_PEAK = 4.0

// A wide blur stays inside these in the pixels it runs in, which is what sets its downscale.
internal const val WIDE_BLUR_MAX_SIGMA = 8.0
internal const val WIDE_BLUR_SUPPORT = 3.0
internal const val WIDE_BLUR_MAX_TAPS = 24

/**
 * Every number the film look is made of, computed once here and consumed by all four lowerings.
 *
 * It all runs on SDR encoded codes. Halation is a blur of [halationResponse] added to the frame's codes. Grain is the
 * same delta on red, green and blue, which leaves Cb and Cr untouched under any matrix, and runs after halation so it
 * lands on the glow too.
 */
@InternalFilmstripApi
public object FilmSignal {
  /**
   * The luma where [halationResponse] reaches its full strength.
   */
  public const val HALATION_FULL_LUMA: Double = HALATION_HIGH

  /**
   * The tick a frame at [seconds] of composition time sits on, at 24 a second.
   */
  public fun grainTickAt(seconds: Double): Int = floor(seconds * GRAIN_TICK_RATE + GRAIN_TICK_SLACK).toInt()

  /**
   * What a frame's size divides a pixel position by, once it is multiplied by the cell scale, to land on a grain cell.
   *
   * Every cell is square. A cell's edge is the larger of the frame's width over 1920 and its height over 1080, which
   * fits the frame inside the raster on either side.
   */
  public fun grainCellSpan(
    width: Int,
    height: Int,
  ): Int = max(width * GRAIN_FIT_X, height * GRAIN_FIT_Y)

  /**
   * The cell on the fixed 1920 by 1080 raster that pixel [x], [y] of a frame [width] by [height] sits in, with [y]
   * counted from the top.
   *
   * @return The cell's index in reading order.
   */
  public fun grainCell(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Int {
    val span = grainCellSpan(width, height).toLong()
    val column = x.toLong() * GRAIN_CELL_SCALE / span
    val row = y.toLong() * GRAIN_CELL_SCALE / span
    return (row * GRAIN_CELLS_ACROSS + column).toInt()
  }

  /**
   * The grain in [cell] at [tick], a triangular value from `-1.0` to `1.0` with a mean of zero, as the sum of two
   * draws.
   */
  public fun grainAt(
    cell: Int,
    tick: Int,
    seed: Int = GRAIN_SEED,
  ): Double =
    FilterNoise.unorm(FilterNoise.hash24(seed, PASS_GRAIN_FIRST, tick, cell)) +
      FilterNoise.unorm(FilterNoise.hash24(seed, PASS_GRAIN_SECOND, tick, cell)) - 1.0

  /**
   * How much of the grain lands on a pixel of Y' code [luma], most in the midtones and a quarter at the least.
   */
  public fun grainWeight(luma: Double): Double = max(GRAIN_WEIGHT_FLOOR, GRAIN_WEIGHT_PEAK * luma * (1.0 - luma))

  /**
   * The light a code triple gives the halation blur, as red, green and blue.
   */
  public fun halationResponse(
    red: Double,
    green: Double,
    blue: Double,
  ): DoubleArray {
    val response = smoothstep(HALATION_LOW, HALATION_HIGH, lumaOf(red, green, blue))
    return doubleArrayOf(response * HALATION_TINT_RED, response * HALATION_TINT_GREEN, response * HALATION_TINT_BLUE)
  }

  /**
   * How to run a blur of [sigmaFraction] of the frame width on a frame [widthPx] wide.
   */
  public fun wideBlurPlan(
    sigmaFraction: Double,
    widthPx: Int,
  ): WideBlurPlan {
    require(sigmaFraction > 0.0) { "A wide blur needs a sigma above 0, not $sigmaFraction." }
    val factor = max(1, ceil(sigmaFraction * widthPx / WIDE_BLUR_MAX_SIGMA).toInt())
    val width = max(1, widthPx / factor)
    val sigma = sigmaFraction * width
    val taps = max(1, min(WIDE_BLUR_MAX_TAPS, ceil(WIDE_BLUR_SUPPORT * sigma).toInt()))
    val raw = DoubleArray(taps + 1) { exp(-0.5 * (it / sigma) * (it / sigma)) }
    val total = raw[0] + 2.0 * raw.drop(1).sum()
    return WideBlurPlan(factor, width, sigma, taps, DoubleArray(taps + 1) { raw[it] / total })
  }

  /**
   * The blur a frame [widthPx] wide runs for its halation.
   */
  public fun halationBlurPlan(widthPx: Int): WideBlurPlan = wideBlurPlan(HALATION_SIGMA, widthPx)

  /**
   * [red], [green] and [blue] with [blurred], the blur of [halationResponse] over this pixel, added at [params]'
   * strength and clamped to 1.
   */
  public fun halated(
    red: Double,
    green: Double,
    blue: Double,
    blurred: DoubleArray,
    params: FilmParams,
  ): DoubleArray =
    doubleArrayOf(
      min(1.0, red + params.halationGain * blurred[0]),
      min(1.0, green + params.halationGain * blurred[1]),
      min(1.0, blue + params.halationGain * blurred[2]),
    )

  /**
   * [red], [green] and [blue] with the grain of [cell] at [tick] added at [params]' strength and clamped to the code
   * range.
   */
  public fun grained(
    red: Double,
    green: Double,
    blue: Double,
    cell: Int,
    tick: Int,
    params: FilmParams,
  ): DoubleArray {
    val delta = params.grainStrength * grainWeight(lumaOf(red, green, blue)) * grainAt(cell, tick)
    return doubleArrayOf(
      (red + delta).coerceIn(0.0, 1.0),
      (green + delta).coerceIn(0.0, 1.0),
      (blue + delta).coerceIn(0.0, 1.0),
    )
  }

  /**
   * The R'G'B' a pixel of a frame ends on, with [halated] run first and [grained] over its result, a pure function a
   * test holds a backend's pixels against.
   */
  public fun pixelAt(
    red: Double,
    green: Double,
    blue: Double,
    blurred: DoubleArray,
    cell: Int,
    tick: Int,
    params: FilmParams,
  ): DoubleArray {
    val glow = halated(red, green, blue, blurred, params)
    return grained(glow[0], glow[1], glow[2], cell, tick, params)
  }
}

/**
 * A Gaussian blur worked out for one frame width, which runs at a smaller size so its sigma stays cheap.
 *
 * @property factor How many frame pixels one blurred pixel spans.
 * @property width The width in pixels the blur runs at.
 * @property sigmaPx The sigma in blurred pixels, at most 8.
 * @property taps How many taps run each side of the centre, out to 3 sigma.
 * @property weights The weight of the centre and then of each tap out from it, which sum to 1 with each side counted.
 */
@InternalFilmstripApi
public class WideBlurPlan internal constructor(
  public val factor: Int,
  public val width: Int,
  public val sigmaPx: Double,
  public val taps: Int,
  public val weights: DoubleArray,
) {
  /**
   * The height in pixels the blur runs at, for a frame [heightPx] tall.
   */
  public fun heightOf(heightPx: Int): Int = max(1, heightPx / factor)
}

/**
 * The strengths of one [Film], in code values.
 *
 * Built by [FilmParams.at], and read by every lowering so they all draw the same frame. The thresholds, sigma and tint
 * stay fixed, so only these two change with the controls.
 *
 * @property grainStrength The largest grain delta, reached on a midtone.
 * @property halationGain How much of the blurred response is added to the frame.
 */
@InternalFilmstripApi
public class FilmParams private constructor(
  public val grainStrength: Double,
  public val halationGain: Double,
) {
  /**
   * Builds the values a [Film] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Film] at [grain] and [halation] draws with, each strength linear in its own control.
     */
    public fun at(
      grain: Double,
      halation: Double,
    ): FilmParams = FilmParams(GRAIN_STRENGTH * grain, HALATION_GAIN * halation)

    /**
     * The values [film] draws with.
     */
    public fun of(film: Film): FilmParams = at(film.grain.toDouble(), film.halation.toDouble())
  }
}
