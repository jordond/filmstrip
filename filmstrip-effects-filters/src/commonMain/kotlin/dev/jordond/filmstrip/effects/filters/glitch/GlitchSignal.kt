package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.hash24
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.snorm
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.unorm
import dev.jordond.filmstrip.effects.filters.internal.straightReadAcross
import kotlin.math.floor
import kotlin.math.min

// The clock and the raster.
internal const val GLITCH_TICK_RATE = 12.0
internal const val GLITCH_TICK_MARGIN = 1e-4
internal const val GLITCH_ROWS = 1080

// One random stream per thing that gets drawn, clear of the VHS streams.
internal const val PASS_BURST = 32
internal const val PASS_SLICE_HEIGHT = 33
internal const val PASS_SLICE_ROLL = 34
internal const val PASS_SLICE_SHIFT = 35
internal const val PASS_TEAR_ROLL = 36
internal const val PASS_TEAR_SHIFT = 37

// The cells PASS_BURST rolls in each window.
internal const val BURST_CELL_FIRES = 0
internal const val BURST_CELL_LENGTH = 1
internal const val BURST_CELL_START = 2

// Shapes that stay fixed at every intensity, in ticks or virtual rows.
internal const val BURST_WINDOW_TICKS = 24
internal const val BURST_MIN_TICKS = 4
internal const val BURST_MAX_TICKS = 10
internal const val ATTACK_TICKS = 3
internal const val DECAY_TICKS = 4
internal const val SLICE_MIN_ROWS = 24
internal const val SLICE_MAX_ROWS = 128
internal const val TEAR_ROWS = 3

// How many lengths a burst and how many heights a slice can take.
internal const val BURST_LENGTHS = BURST_MAX_TICKS - BURST_MIN_TICKS + 1
internal const val SLICE_SPREAD = SLICE_MAX_ROWS - SLICE_MIN_ROWS + 1

// A roll lands under its chance when its 24 bit hash is under these.
internal const val SLICE_SHIFT_BELOW = 1 shl 23
internal const val TEAR_BELOW = 335_544

// A draw of n whole steps is the 24 bit hash times n, shifted down 24 bits, which stays exact in 32 bit integers.
internal const val HASH_BITS = 24

/**
 * Every number the glitch look is made of, computed once here and consumed by all four lowerings.
 *
 * Positions are `x` in fractions of the frame width and `row` on a 1080 line virtual raster, and time is a [tickAt]
 * of the frame's composition time, so the pattern does not move with a seek or a render size. Every random draw is one
 * [FilterNoise.hash24].
 */
@InternalFilmstripApi
public object GlitchSignal {
  /**
   * How many rows the virtual raster has.
   */
  public const val ROWS: Int = GLITCH_ROWS

  /**
   * The tick a frame at [seconds] of composition time sits on, twelve a second. The margin holds a frame that sits on
   * an opening against double rounding, so it reads the tick it opens.
   */
  public fun tickAt(seconds: Double): Int = floor(seconds * GLITCH_TICK_RATE + GLITCH_TICK_MARGIN).toInt()

  /**
   * The virtual row that pixel row [y] of a frame [height] pixels tall sits on, counted from the top.
   */
  public fun virtualRow(
    y: Int,
    height: Int,
  ): Int = (y.toLong() * GLITCH_ROWS / height).toInt()

  /**
   * The burst running at [tick], or null when none is.
   *
   * Ticks fall into windows of [BURST_WINDOW_TICKS]. One roll per window says whether it bursts, a second how many
   * ticks the burst lasts and a third which tick of the window it starts on. Its strength rises over the first
   * [ATTACK_TICKS] and falls over the last [DECAY_TICKS].
   */
  public fun burstAt(
    seed: Int,
    tick: Int,
    params: GlitchParams,
  ): Burst? {
    val window = tick.floorDiv(BURST_WINDOW_TICKS)
    if (unorm(hash24(seed, PASS_BURST, window, BURST_CELL_FIRES)) >= params.burstChance) return null
    val length = BURST_MIN_TICKS + steps(hash24(seed, PASS_BURST, window, BURST_CELL_LENGTH), BURST_LENGTHS)
    val start = steps(hash24(seed, PASS_BURST, window, BURST_CELL_START), BURST_WINDOW_TICKS - length + 1)
    val into = tick - window * BURST_WINDOW_TICKS - start
    if (into < 0 || into >= length) return null
    val attack = min(1.0, (into + 1).toDouble() / ATTACK_TICKS)
    val decay = min(1.0, (length - into).toDouble() / DECAY_TICKS)
    return Burst(attack * decay)
  }

  /**
   * The slices the frame is cut into at [tick], top to bottom, covering every virtual row.
   *
   * Each slice is between [SLICE_MIN_ROWS] and [SLICE_MAX_ROWS] rows tall, the last cut off at the bottom of the
   * frame. About half of them slide, by up to [GlitchParams.bandShift] either way times the [burst]'s strength.
   */
  public fun slices(
    seed: Int,
    tick: Int,
    burst: Burst?,
    params: GlitchParams,
  ): List<Slice> {
    val slices = mutableListOf<Slice>()
    var top = 0
    var index = 0
    while (top < GLITCH_ROWS) {
      val bottom = min(top + sliceRows(seed, tick, index), GLITCH_ROWS)
      slices += Slice(top, bottom, sliceShift(seed, tick, index, burst, params))
      top = bottom
      index++
    }
    return slices
  }

  /**
   * How far a tear slides [row] at [tick], a fraction of the width, 0 when no tear runs there.
   *
   * A tear covers [TEAR_ROWS] virtual rows, and about one cell in fifty tears during a burst.
   */
  public fun tearAt(
    seed: Int,
    tick: Int,
    row: Int,
    burst: Burst?,
    params: GlitchParams,
  ): Double {
    if (burst == null) return 0.0
    val cell = row / TEAR_ROWS
    if (hash24(seed, PASS_TEAR_ROLL, tick, cell) >= TEAR_BELOW) return 0.0
    return params.tearShift * burst.strength * snorm(hash24(seed, PASS_TEAR_SHIFT, tick, cell))
  }

  /**
   * How far [row] slides right at [tick], a fraction of the width. Its slice's slide plus any tear, 0 outside a burst.
   */
  public fun rowShift(
    seed: Int,
    tick: Int,
    row: Int,
    params: GlitchParams,
  ): Double {
    val burst = burstAt(seed, tick, params) ?: return 0.0
    var top = 0
    var index = 0
    while (true) {
      val bottom = top + sliceRows(seed, tick, index)
      if (row < bottom) break
      top = bottom
      index++
    }
    return sliceShift(seed, tick, index, burst, params) + tearAt(seed, tick, row, burst, params)
  }

  /**
   * How far red sits right of green at [tick] and blue left of it, a fraction of the width. A steady
   * [GlitchParams.split], and [GlitchParams.burstSplit] more times the strength of any burst.
   */
  public fun channelSplit(
    seed: Int,
    tick: Int,
    params: GlitchParams,
  ): Double = params.split + params.burstSplit * (burstAt(seed, tick, params)?.strength ?: 0.0)

  /**
   * The colour a lowering draws at [x] on [row] at [tick], a pure function a test holds a backend's pixels against.
   *
   * [texel] gives the straight red, green and blue of a column of the row in a frame [width] pixels wide, with alpha
   * after them when the frame has one. Green and alpha read at `x` less the row's shift, red a split further left and
   * blue a split further right. Each read mixes the two columns either side premultiplied, held at the edge of the
   * frame, and comes back straight, so a clear column lends its neighbour no colour.
   *
   * @return red, green and blue, with alpha after them when [texel] gives one.
   */
  public fun pixelAt(
    x: Double,
    row: Int,
    tick: Int,
    seed: Int,
    width: Int,
    params: GlitchParams,
    texel: (column: Int) -> DoubleArray,
  ): DoubleArray {
    val shifted = x - rowShift(seed, tick, row, params)
    val split = channelSplit(seed, tick, params)
    val read = { at: Double -> straightReadAcross(at.coerceIn(0.0, 1.0), width, texel) }
    val green = read(shifted)
    val out = green.copyOf()
    out[0] = read(shifted - split)[0]
    out[2] = read(shifted + split)[2]
    return out
  }

  /**
   * The most any read moves from its own column, a fraction of the width.
   */
  public fun widestReach(params: GlitchParams): Double = params.widestReach

  /**
   * The most slices a frame can be cut into, which bounds a shader's search for a row's slice.
   */
  public val maxSlices: Int = (GLITCH_ROWS + SLICE_MIN_ROWS - 1) / SLICE_MIN_ROWS

  private fun sliceRows(
    seed: Int,
    tick: Int,
    index: Int,
  ): Int = SLICE_MIN_ROWS + steps(hash24(seed, PASS_SLICE_HEIGHT, tick, index), SLICE_SPREAD)

  private fun sliceShift(
    seed: Int,
    tick: Int,
    index: Int,
    burst: Burst?,
    params: GlitchParams,
  ): Double {
    if (burst == null || hash24(seed, PASS_SLICE_ROLL, tick, index) >= SLICE_SHIFT_BELOW) return 0.0
    return params.bandShift * burst.strength * snorm(hash24(seed, PASS_SLICE_SHIFT, tick, index))
  }

  // A hash as one of [count] whole steps from 0.
  private fun steps(
    hash: Int,
    count: Int,
  ): Int = ((hash.toLong() * count) ushr HASH_BITS).toInt()
}

/**
 * A burst on one tick.
 *
 * @property strength How far into the burst the tick sits, from just over 0 at its edges to 1 at its height.
 */
@InternalFilmstripApi
public class Burst(
  public val strength: Double,
)

/**
 * One horizontal slice of the frame on a tick.
 *
 * @property top The first virtual row the slice covers.
 * @property bottom The virtual row after its last.
 * @property shift How far the slice slides right, a fraction of the width.
 */
@InternalFilmstripApi
public class Slice(
  public val top: Int,
  public val bottom: Int,
  public val shift: Double,
)
