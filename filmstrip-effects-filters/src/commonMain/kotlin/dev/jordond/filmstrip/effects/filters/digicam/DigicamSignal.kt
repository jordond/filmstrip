package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CLOCK_BASE
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CLOCK_ROUNDING
import dev.jordond.filmstrip.effects.filters.film.GRAIN_TICK_MARGIN
import dev.jordond.filmstrip.effects.filters.glitch.GLITCH_ROWS
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.GlowField
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The halo's blur as a fraction of the frame width, a pixel and a half at 1920, and the gain at a sharpness of 1.
internal const val HALO_SIGMA_FRACTION = 1.5 / 1920.0
internal const val HALO_GAIN_MAX = 2.0

// What a clip of 1 adds to the gain every code is multiplied by.
internal const val CLIP_GAIN_MAX = 0.6

// The largest chroma offset at a noise of 1, the side of a noise cell in rows of the 1080 row virtual raster, and how
// many times a second the noise changes.
internal const val NOISE_MAX = 0.3
internal const val NOISE_ROWS = 3
internal const val NOISE_RATE = 30.0

// One random stream per chroma axis, clear of every other filter's streams.
internal const val PASS_NOISE_CB = 50
internal const val PASS_NOISE_CR = 51

// How many noise cells a row holds before the cell index wraps onto the next row.
internal const val NOISE_CELLS_ACROSS = 8192

// What a chroma offset moves blue and red by under BT.709.
internal const val CB_TO_BLUE = 2.0 * (1.0 - KB)
internal const val CR_TO_RED = 2.0 * (1.0 - KR)

/**
 * Every number a digicam look is made of, computed once here and consumed by all four lowerings.
 *
 * It all runs on SDR encoded codes from 0 to 1. A pixel is sharpened against a narrow blur of the frame, then pushed
 * until it clips, then given the chroma noise of its cell.
 */
@InternalFilmstripApi
public object DigicamSignal {
  /**
   * The blur the halo is measured against on a frame [widthPx] wide, which runs at the frame's own size.
   */
  public fun blurPlan(widthPx: Int): BlurPlan = GlowField.blurPlan(HALO_SIGMA_FRACTION, widthPx)

  /**
   * The blur of a frame [width] by [height], for [pixelAt] to read at each pixel.
   *
   * @param pixel Reads the R'G'B' of a pixel counted from the top, already multiplied by its alpha.
   */
  public fun blur(
    width: Int,
    height: Int,
    pixel: (x: Int, y: Int) -> DoubleArray,
  ): GlowField =
    GlowField.of(width, height, HALO_SIGMA_FRACTION, { red, green, blue -> doubleArrayOf(red, green, blue) }, pixel)

  /**
   * [code] moved away from [blurred], the blur of the frame over it, by [params]' halo gain and held within 0 to 1.
   */
  public fun sharpened(
    code: Double,
    blurred: Double,
    params: DigicamParams,
  ): Double = (code + params.haloGain * (code - blurred)).coerceIn(0.0, 1.0)

  /**
   * [code] multiplied by [params]' clip gain and cut off at white.
   */
  public fun clipped(
    code: Double,
    params: DigicamParams,
  ): Double = min(1.0, code * params.clipGain)

  /**
   * The side of a noise cell in pixels on a frame [height] pixels tall, [NOISE_ROWS] of the virtual raster rounded to
   * the nearest pixel in whole numbers.
   */
  public fun cellPx(height: Int): Int = max(1, (height * 2 * NOISE_ROWS + GLITCH_ROWS) / (2 * GLITCH_ROWS))

  /**
   * The noise cell pixel [x], [y] of a frame [height] pixels tall sits in, counted from the top left.
   */
  public fun cellOf(
    x: Int,
    y: Int,
    height: Int,
  ): Int {
    val side = cellPx(height)
    return y / side * NOISE_CELLS_ACROSS + x / side
  }

  /**
   * The chroma noise of [cell] at [tick], as a blue difference and a red difference each from `-1.0` to `1.0`.
   */
  public fun chromaAt(
    seed: Int,
    tick: Int,
    cell: Int,
  ): DoubleArray =
    doubleArrayOf(
      FilterNoise.snorm(FilterNoise.hash24(seed, PASS_NOISE_CB, tick, cell)),
      FilterNoise.snorm(FilterNoise.hash24(seed, PASS_NOISE_CR, tick, cell)),
    )

  /**
   * How much of [params]' noise a pixel of [luma] takes, the whole of it at black and none at white.
   */
  public fun noiseWeight(
    luma: Double,
    params: DigicamParams,
  ): Double = params.noiseAmplitude * (1.0 - luma) * (1.0 - luma)

  /**
   * [red], [green] and [blue] with the chroma noise [chroma] added at the weight of their own luma, which the noise
   * leaves as it was until a channel reaches black or white.
   *
   * @param chroma The blue and red difference [chromaAt] gives for the pixel's cell.
   */
  public fun noised(
    red: Double,
    green: Double,
    blue: Double,
    chroma: DoubleArray,
    params: DigicamParams,
  ): DoubleArray {
    val weight = noiseWeight(lumaOf(red, green, blue), params)
    val moveBlue = CB_TO_BLUE * weight * chroma[0]
    val moveRed = CR_TO_RED * weight * chroma[1]
    val moveGreen = -(KR * moveRed + KB * moveBlue) / (1.0 - KR - KB)
    return doubleArrayOf(
      (red + moveRed).coerceIn(0.0, 1.0),
      (green + moveGreen).coerceIn(0.0, 1.0),
      (blue + moveBlue).coerceIn(0.0, 1.0),
    )
  }

  /**
   * The tick a frame at [seconds] of composition time sits on, at 30 a second.
   *
   * The time reads to its nearest whole microsecond first, on every backend, and a tick opens on the whole microsecond
   * nearest its exact time. A frame that sits on a tick reads that tick.
   */
  public fun tickAt(seconds: Double): Int {
    val microseconds = floor(seconds * GRAIN_CLOCK_BASE + GRAIN_CLOCK_ROUNDING)
    return floor((microseconds + GRAIN_CLOCK_ROUNDING) / GRAIN_CLOCK_BASE * NOISE_RATE + GRAIN_TICK_MARGIN).toInt()
  }

  /**
   * The expression for the whole microsecond the tick named by the expression [tick] opens on under [tickAt].
   */
  public fun tickOpening(tick: String): String = "ceil(($tick)*$GRAIN_CLOCK_BASE/$NOISE_RATE-$GRAIN_CLOCK_ROUNDING)"

  /**
   * Pixel [x], [y] of a frame [height] pixels tall at [tick], as red, green and blue.
   *
   * @param rgb The pixel's own R'G'B'.
   * @param blurred The blur of the frame over the pixel, which [blur] gives.
   */
  public fun pixelAt(
    x: Int,
    y: Int,
    height: Int,
    seed: Int,
    tick: Int,
    params: DigicamParams,
    rgb: DoubleArray,
    blurred: DoubleArray,
  ): DoubleArray {
    val pushed = DoubleArray(rgb.size) { clipped(sharpened(rgb[it], blurred[it], params), params) }
    return noised(pushed[0], pushed[1], pushed[2], chromaAt(seed, tick, cellOf(x, y, height)), params)
  }
}
