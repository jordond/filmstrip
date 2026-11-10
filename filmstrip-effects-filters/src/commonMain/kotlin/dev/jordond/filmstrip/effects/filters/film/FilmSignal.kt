package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.lumaOf
import dev.jordond.filmstrip.effects.filters.internal.smoothstep
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Strengths at 1, and the shapes that stay fixed at every strength.
private const val GRAIN_STRENGTH = 0.07
private const val HALATION_GAIN = 0.35
internal const val HALATION_LOW = 0.7
internal const val HALATION_HIGH = 0.95
internal const val HALATION_SIGMA = 0.012
internal const val HALATION_TINT_RED = 1.0
internal const val HALATION_TINT_GREEN = 0.25
internal const val HALATION_TINT_BLUE = 0.08

// The grain's clock. A frame's time reads on whole microseconds, the clock ffmpeg holds a live plane's stamp against,
// and a tick opens on the whole microsecond nearest its exact time. The margin holds that reading against double
// rounding.
internal const val GRAIN_TICK_RATE = 24.0
internal const val GRAIN_TICK_MARGIN = 1e-9
internal const val GRAIN_CLOCK_BASE = 1_000_000.0
internal const val GRAIN_CLOCK_ROUNDING = 0.5

// The grain's raster is 1920 by 1080 cells. Its fit on a frame, and how a pixel lands in a cell, stays in integers. A
// cell's edge is frame pixels over GRAIN_CELL_SCALE, which is 1080 * 1920 / 120, with 120 taken out of the raster's
// two sides so the product holds in 32 bits at any frame size.
internal const val GRAIN_CELLS_ACROSS = 1920
internal const val GRAIN_CELLS_DOWN = 1080
internal const val GRAIN_CELL_SCALE = 17_280
internal const val GRAIN_FIT_X = 9
internal const val GRAIN_FIT_Y = 16

// One random stream for each of the two draws a grain value sums.
internal const val PASS_GRAIN_FIRST = 1
internal const val PASS_GRAIN_SECOND = 2
internal const val GRAIN_SEED = Film.DEFAULT_SEED

// grainWeight's shape, a parabola that peaks in the midtones over a floor that keeps grain in the shadows and
// highlights.
internal const val GRAIN_WEIGHT_FLOOR = 0.25
internal const val GRAIN_WEIGHT_PEAK = 4.0

// The gate's weave. A knot every WEAVE_KNOT_TICKS ticks holds a random offset, and the ticks between ease from one to
// the next. The reach is the largest offset at a weave of 1, as a fraction of the frame, twice as far up and down.
internal const val WEAVE_KNOT_TICKS = 6
internal const val WEAVE_REACH_X = 0.0015
internal const val WEAVE_REACH_Y = 0.003
internal const val PASS_WEAVE_X = 3
internal const val PASS_WEAVE_Y = 4

// The flicker's largest change of gain at a flicker of 1.
internal const val FLICKER_DEPTH = 0.08
internal const val PASS_FLICKER = 5

// Dust lands on a coarse raster of DUST_CELL by DUST_CELL grain cells, at most one speck in each, DUST_PER_FRAME specks
// a frame on average at a dust of 1. A speck is a disc around its dust cell's centre, its radius in grain cells.
internal const val DUST_CELL = 8
internal const val DUST_CELLS_ACROSS = GRAIN_CELLS_ACROSS / DUST_CELL
internal const val DUST_CELLS = DUST_CELLS_ACROSS * (GRAIN_CELLS_DOWN / DUST_CELL)
internal const val DUST_PER_FRAME = 12.0
internal const val DUST_RADIUS_MIN = 1.0
internal const val DUST_RADIUS_MAX = 3.5
internal const val DUST_LEVEL = 0.35
internal const val PASS_DUST = 6
internal const val PASS_DUST_SIZE = 7
internal const val PASS_DUST_TONE = 8

// A scratch lives inside a run of SCRATCH_RUN_TICKS ticks, for a hashed life of SCRATCH_LIFE_MIN to SCRATCH_LIFE_MAX
// ticks. Its place is a fraction of the frame's width that drifts by up to SCRATCH_DRIFT a tick.
internal const val SCRATCH_RUN_TICKS = 48
internal const val SCRATCH_LIFE_MIN = 12
internal const val SCRATCH_LIFE_MAX = 36
internal const val SCRATCH_EDGE = 0.1
internal const val SCRATCH_WIDTH = 0.0012
internal const val SCRATCH_DRIFT = 0.0004
internal const val SCRATCH_LEVEL = 0.25
internal const val PASS_SCRATCH = 9
internal const val PASS_SCRATCH_LIFE = 10
internal const val PASS_SCRATCH_START = 11
internal const val PASS_SCRATCH_PLACE = 12
internal const val PASS_SCRATCH_DRIFT = 13

// The gate at a gate of 1, as fractions of the frame's height: how far its edge sits in from the frame's, its corner
// radius, and how far either side of its edge the falloff reaches.
internal const val GATE_INSET = 0.03
internal const val GATE_RADIUS = 0.08
internal const val GATE_SOFTNESS = 0.01
internal const val FRAME_CENTRE = 0.5

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
   *
   * The time reads to its nearest whole microsecond first, on every backend, and a tick opens on the whole microsecond
   * nearest its exact time, the one [grainTickOpening] writes. A frame that sits on a tick reads that tick.
   */
  public fun grainTickAt(seconds: Double): Int {
    val microseconds = floor(seconds * GRAIN_CLOCK_BASE + GRAIN_CLOCK_ROUNDING)
    return floor((microseconds + GRAIN_CLOCK_ROUNDING) / GRAIN_CLOCK_BASE * GRAIN_TICK_RATE + GRAIN_TICK_MARGIN).toInt()
  }

  /**
   * The expression for the whole microsecond the tick named by the expression [tick] opens on under [grainTickAt].
   */
  public fun grainTickOpening(tick: String): String =
    "ceil(($tick)*$GRAIN_CLOCK_BASE/$GRAIN_TICK_RATE-$GRAIN_CLOCK_ROUNDING)"

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
  ): BlurPlan = BlurPlan.of(sigmaFraction, widthPx)

  /**
   * The blur a frame [widthPx] wide runs for its halation.
   */
  public fun halationBlurPlan(widthPx: Int): BlurPlan = wideBlurPlan(HALATION_SIGMA, widthPx)

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
    val delta = params.grainStrength * grainWeight(lumaOf(red, green, blue)) * grainAt(cell, tick, params.seed)
    return doubleArrayOf(
      (red + delta).coerceIn(0.0, 1.0),
      (green + delta).coerceIn(0.0, 1.0),
      (blue + delta).coerceIn(0.0, 1.0),
    )
  }

  /**
   * How far the frame at [tick] has wandered in the gate under [seed], at a [weave] from 0 to 1.
   *
   * A knot every six ticks holds a random offset, and the ticks between ease from one knot to the next, so the frame
   * drifts rather than jumps. A pixel reads the frame at its own place plus this offset, held at the frame's edge.
   *
   * @return The offset across and down, as fractions of the frame's width and height, the second reaching twice as far.
   */
  public fun weaveAt(
    seed: Int,
    tick: Int,
    weave: Double,
  ): DoubleArray {
    val knot = floorDiv(tick, WEAVE_KNOT_TICKS)
    val f = (tick - knot * WEAVE_KNOT_TICKS).toDouble() / WEAVE_KNOT_TICKS
    val ease = f * f * (3.0 - 2.0 * f)
    val across = knotted(seed, PASS_WEAVE_X, knot, ease)
    val down = knotted(seed, PASS_WEAVE_Y, knot, ease)
    return doubleArrayOf(weave * WEAVE_REACH_X * across, weave * WEAVE_REACH_Y * down)
  }

  /**
   * The gain the whole frame at [tick] is multiplied by under [seed], around 1 by up to 8 percent at a [flicker] of 1.
   */
  public fun flickerAt(
    seed: Int,
    tick: Int,
    flicker: Double,
  ): Double = 1.0 + FLICKER_DEPTH * flicker * FilterNoise.snorm(FilterNoise.hash24(seed, PASS_FLICKER, tick, 0))

  /**
   * What a speck of dust adds to the codes of grain [cell] at [tick] under [seed], at a [dust] from 0 to 1.
   *
   * Every eight by eight block of grain cells holds a speck at a tick by chance, about twelve a frame at a dust of 1,
   * and each lasts that tick alone. A speck is a disc around its block's centre, dark or light at random.
   *
   * @return The change to each of red, green and blue, 0 off every speck.
   */
  public fun dustAt(
    seed: Int,
    tick: Int,
    cell: Int,
    dust: Double,
  ): Double {
    val column = cell % GRAIN_CELLS_ACROSS
    val row = cell / GRAIN_CELLS_ACROSS
    val block = (row / DUST_CELL) * DUST_CELLS_ACROSS + column / DUST_CELL
    val chance = DUST_PER_FRAME * dust / DUST_CELLS
    if (FilterNoise.unorm(FilterNoise.hash24(seed, PASS_DUST, tick, block)) >= chance) return 0.0
    val radius =
      DUST_RADIUS_MIN +
        (DUST_RADIUS_MAX - DUST_RADIUS_MIN) * FilterNoise.unorm(FilterNoise.hash24(seed, PASS_DUST_SIZE, tick, block))
    val dx = column % DUST_CELL + 0.5 - DUST_CELL / 2.0
    val dy = row % DUST_CELL + 0.5 - DUST_CELL / 2.0
    if (dx * dx + dy * dy > radius * radius) return 0.0
    val light = FilterNoise.unorm(FilterNoise.hash24(seed, PASS_DUST_TONE, tick, block)) < HALF
    return if (light) DUST_LEVEL else -DUST_LEVEL
  }

  /**
   * What a scratch adds to the codes at [x] across the frame, as a fraction of its width, at [tick] under [seed], at a
   * [dust] from 0 to 1.
   *
   * Each run of 48 ticks holds a scratch with a chance of [dust]. It lives for a hashed 12 to 36 ticks of the run, a
   * thin light line down the whole frame that drifts slowly sideways while it lives.
   *
   * @return The change to each of red, green and blue, 0 off the scratch.
   */
  public fun scratchAt(
    seed: Int,
    tick: Int,
    x: Double,
    dust: Double,
  ): Double {
    val run = floorDiv(tick, SCRATCH_RUN_TICKS)
    if (FilterNoise.unorm(FilterNoise.hash24(seed, PASS_SCRATCH, run, 0)) >= dust) return 0.0
    val lives = SCRATCH_LIFE_MAX - SCRATCH_LIFE_MIN + 1
    val life = SCRATCH_LIFE_MIN + floor(FilterNoise.unorm(FilterNoise.hash24(seed, PASS_SCRATCH_LIFE, run, 0)) * lives)
    val start =
      floor(FilterNoise.unorm(FilterNoise.hash24(seed, PASS_SCRATCH_START, run, 0)) * (SCRATCH_RUN_TICKS - life + 1))
    val age = tick - run * SCRATCH_RUN_TICKS - start
    if (age < 0.0 || age >= life) return 0.0
    val place =
      SCRATCH_EDGE +
        (1.0 - 2.0 * SCRATCH_EDGE) * FilterNoise.unorm(FilterNoise.hash24(seed, PASS_SCRATCH_PLACE, run, 0))
    val drift = SCRATCH_DRIFT * FilterNoise.snorm(FilterNoise.hash24(seed, PASS_SCRATCH_DRIFT, run, 0))
    return if (abs(x - place - drift * age) <= SCRATCH_WIDTH / 2.0) SCRATCH_LEVEL else 0.0
  }

  /**
   * What the gate multiplies the codes at [x], [y] by, as fractions of the frame, at a [gate] from 0 to 1.
   *
   * The gate is a rounded rectangle inset from the frame's edge, its inset, corner radius and soft edge all growing
   * with [gate], and black outside. The distance from its edge is measured with x scaled by [aspect] so the corners
   * are round on screen, and the codes fall off along a smoothstep across the soft edge, the way a vignette's do. A
   * [gate] of 0 leaves every pixel as it was.
   *
   * @param aspect The frame's width over its height.
   */
  public fun gateMask(
    x: Double,
    y: Double,
    aspect: Double,
    gate: Double,
  ): Double {
    if (gate <= 0.0) return 1.0
    val inset = GATE_INSET * gate
    val radius = GATE_RADIUS * gate
    val softness = GATE_SOFTNESS * gate
    val qx = abs(x - FRAME_CENTRE) * aspect - (FRAME_CENTRE * aspect - inset - radius)
    val qy = abs(y - FRAME_CENTRE) - (FRAME_CENTRE - inset - radius)
    val outside = max(qx, 0.0)
    val below = max(qy, 0.0)
    val distance = sqrt(outside * outside + below * below) + min(max(qx, qy), 0.0) - radius
    return 1.0 - smoothstep(-softness, softness, distance)
  }

  /**
   * The R'G'B' a pixel of a frame ends on, a pure function a test holds a backend's pixels against.
   *
   * [red], [green], [blue] and [blurred] are read off the frame already moved by [weaveAt]. [halated] runs first, then
   * the frame takes [flickerAt]'s gain, then [grained], then [dustAt] and [scratchAt], and [gateMask] last. Every step
   * past the halation leaves the pixel as it was with its control at 0.
   *
   * @param x Where the pixel sits across the frame, as a fraction of its width.
   * @param y Where the pixel sits down the frame, as a fraction of its height.
   * @param aspect The frame's width over its height.
   */
  public fun pixelAt(
    red: Double,
    green: Double,
    blue: Double,
    blurred: DoubleArray,
    cell: Int,
    tick: Int,
    params: FilmParams,
    x: Double = FRAME_CENTRE,
    y: Double = FRAME_CENTRE,
    aspect: Double = 1.0,
  ): DoubleArray {
    val glow = halated(red, green, blue, blurred, params)
    val gain = flickerAt(params.seed, tick, params.flicker)
    val lit = DoubleArray(glow.size) { min(1.0, glow[it] * gain) }
    val grain = grained(lit[0], lit[1], lit[2], cell, tick, params)
    val mark = dustAt(params.seed, tick, cell, params.dust) + scratchAt(params.seed, tick, x, params.dust)
    val gate = gateMask(x, y, aspect, params.gate)
    return DoubleArray(grain.size) { (grain[it] + mark).coerceIn(0.0, 1.0) * gate }
  }

  private fun knotted(
    seed: Int,
    pass: Int,
    knot: Int,
    ease: Double,
  ): Double {
    val from = FilterNoise.snorm(FilterNoise.hash24(seed, pass, knot, 0))
    val to = FilterNoise.snorm(FilterNoise.hash24(seed, pass, knot + 1, 0))
    return from + (to - from) * ease
  }

  private fun floorDiv(
    value: Int,
    by: Int,
  ): Int = floor(value.toDouble() / by).toInt()
}

private const val HALF = 0.5

/**
 * The strengths of one [Film], in code values, and the controls its gate reads.
 *
 * Built by [FilmParams.at], and read by every lowering so they all draw the same frame. The thresholds, sigma, tint
 * and every reach stay fixed, so only these change with the controls.
 *
 * @property grainStrength The largest grain delta, reached on a midtone.
 * @property halationGain How much of the blurred response is added to the frame.
 * @property weave How far the frame wanders in the gate, from 0 to 1, which [FilmSignal.weaveAt] reads.
 * @property flicker How much the frame's brightness flickers, from 0 to 1, which [FilmSignal.flickerAt] reads.
 * @property dust How much dust and how many scratches land, from 0 to 1, which [FilmSignal.dustAt] and
 * [FilmSignal.scratchAt] read.
 * @property gate How far the gate's rounded edge closes in, from 0 to 1, which [FilmSignal.gateMask] reads.
 * @property seed The seed every random draw of the look takes, the grain's too.
 */
@InternalFilmstripApi
public class FilmParams private constructor(
  public val grainStrength: Double,
  public val halationGain: Double,
  public val weave: Double,
  public val flicker: Double,
  public val dust: Double,
  public val gate: Double,
  public val seed: Int,
) {
  /**
   * Builds the values a [Film] draws with.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The values a [Film] at these controls draws with, each strength linear in its own control.
     */
    public fun at(
      grain: Double,
      halation: Double,
      weave: Double = 0.0,
      flicker: Double = 0.0,
      dust: Double = 0.0,
      gate: Double = 0.0,
      seed: Int = Film.DEFAULT_SEED,
    ): FilmParams = FilmParams(GRAIN_STRENGTH * grain, HALATION_GAIN * halation, weave, flicker, dust, gate, seed)

    /**
     * The values [film] draws with.
     */
    public fun of(film: Film): FilmParams =
      at(
        film.grain.toDouble(),
        film.halation.toDouble(),
        film.weave.toDouble(),
        film.flicker.toDouble(),
        film.dust.toDouble(),
        film.gate.toDouble(),
        film.seed,
      )
  }
}
