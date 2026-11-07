package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.hash24
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.snorm
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.unorm
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The clock and the raster.
internal const val TICK_RATE = 59.94
internal const val VIRTUAL_ROWS = 480

// How a frame's time lands on a tick. The time reads on whole microseconds, the clock ffmpeg holds a live plane's stamp
// against, and rounds to the nearest tick, which opens on the whole microsecond nearest its exact time. The margin
// holds that reading against double rounding and sits under the 1e-8 of a tick between any opening and the middle of a
// microsecond.
internal const val TICK_ROUNDING = 0.5
internal const val TICK_MARGIN = 1e-9
internal const val TICK_CLOCK_BASE = 1_000_000.0
internal const val TICK_CLOCK_ROUNDING = 0.5

// The two figures sigmaFor is made of: cycles of picture width per MHz, and sigma times frequency at -3 dB.
private const val CYCLES_PER_MHZ = 52.0
private const val SIGMA_AT_CUTOFF = 0.1325

// One random stream per thing that gets drawn.
internal const val PASS_JITTER = 1
internal const val PASS_WAVE = 2
internal const val PASS_HEAD = 3
internal const val PASS_TRACK_EVENT = 4
internal const val PASS_TRACK_SHIFT = 5
internal const val PASS_LUMA_NOISE = 6
internal const val PASS_CB_NOISE = 7
internal const val PASS_CR_NOISE = 8
internal const val PASS_DROP_EVENT = 9
internal const val PASS_DROP_POS = 10
internal const val PASS_DROP_LEN = 11
internal const val PASS_TRACK_NOISE = 12

// Shapes that stay fixed at every intensity.
internal const val WAVE_CYCLES = 1.5
internal const val WAVE_HZ = 0.2
internal const val WAVE_FIRST = 0.65
internal const val WAVE_SECOND_CYCLES = 2.3
internal const val WAVE_SECOND_HZ = -0.7
internal const val HEAD_BAND = 0.015
internal const val HEAD_OFFSET = 1.0
internal const val HEAD_WOBBLE = 0.15
internal const val HEAD_POWER = 1.5
internal const val HALO_SIGMA = 0.003
internal const val STREAK = 0.035
internal const val CHROMA_NOISE_RATIO = 3.0
internal const val CHROMA_COARSENESS = 3.0
internal const val CHROMA_NOISE_ROWS = 2
internal const val DROPOUT_LENGTH = 0.03
internal const val DROPOUT_LENGTH_MIN = 0.4
internal const val DROPOUT_LENGTH_SPREAD = 1.2
internal const val DROPOUT_RISE = 0.1
internal const val DROPOUT_FALL = 0.6
internal const val TRACK_WINDOW_SECONDS = 4.0
internal const val TRACK_BAND = 0.07
internal const val TRACK_DRIFT = 0.05
internal const val TRACK_NOISE_CELL = 0.008
internal const val TRACK_CHROMA_LOSS = 0.5
internal const val ROW_CELL_STRIDE = 1024

// The blur's tap tables, as a count each side of the pixel and a spacing in sigmas.
internal const val LUMA_TAPS = 4
internal const val LUMA_TAP_STEP = 0.75
internal const val CHROMA_TAPS = 8
internal const val CHROMA_TAP_STEP = 0.375

private const val TAU = 2.0 * PI

/**
 * Every number the VHS look is made of, computed once here and consumed by all four lowerings.
 *
 * Positions are `x` in fractions of the frame width and `row` on the 480 line virtual raster, and time is a [tickAt] of
 * the frame's composition time, so the pattern does not move with a seek or a render size. Every random draw is one
 * [FilterNoise.hash24].
 */
@InternalFilmstripApi
public object VhsSignal {
  /**
   * The tick a frame at [seconds] of composition time sits on, rounded to the nearest.
   *
   * The time reads to its nearest whole microsecond first, on every backend, and a tick opens on the whole microsecond
   * nearest its exact time, the one [tickOpening] writes. A frame that sits on an opening reads the tick it opens.
   *
   * The clock runs at exactly 59.94, not 60000/1001, so a clip on a 1001 rate drifts across the openings. A 23.976 clip
   * has odd frames within half a microsecond under a half tick, and those read the later tick.
   */
  public fun tickAt(seconds: Double): Int {
    val microseconds = floor(seconds * TICK_CLOCK_BASE + TICK_CLOCK_ROUNDING)
    return floor(
      (microseconds + TICK_CLOCK_ROUNDING) / TICK_CLOCK_BASE * TICK_RATE + TICK_ROUNDING + TICK_MARGIN,
    ).toInt()
  }

  /**
   * The expression for the whole microsecond the tick named by the expression [tick] opens on under [tickAt].
   */
  public fun tickOpening(tick: String): String =
    "ceil(($tick-$TICK_ROUNDING)*$TICK_CLOCK_BASE/$TICK_RATE-$TICK_CLOCK_ROUNDING)"

  /**
   * The virtual row that pixel row [y] of a frame [height] pixels tall sits on, counted from the top.
   */
  public fun virtualRow(
    y: Int,
    height: Int,
  ): Int = (y.toLong() * VIRTUAL_ROWS / height).toInt()

  /**
   * The Gaussian sigma that lands -3 dB on a cutoff of [mhz], a fraction of the frame width.
   */
  public fun sigmaFor(mhz: Double): Double = SIGMA_AT_CUTOFF / (mhz * CYCLES_PER_MHZ)

  /**
   * Weight [index] of a tap table spaced [step] sigmas apart, not yet normalised.
   */
  public fun tapWeight(
    index: Int,
    step: Double,
  ): Double = exp(-0.5 * (index * step).pow(2))

  /**
   * The sum of a tap table with [taps] each side of the centre, which divides every weight.
   */
  public fun tapSum(
    taps: Int,
    step: Double,
  ): Double = 1.0 + 2.0 * (1..taps).sumOf { tapWeight(it, step) }

  /**
   * How much of the tracking band falls on [row] at [tick], from 0 to 1.
   *
   * Each window rolls once for whether a band runs in it and where it starts. A band drifts and fades in and out over
   * the window.
   */
  public fun trackingWeight(
    seed: Int,
    tick: Int,
    row: Int,
    params: VhsParams,
  ): Double {
    val window = tick / trackWindowTicks
    if (unorm(hash24(seed, PASS_TRACK_EVENT, window, 0)) >= params.trackingChance) return 0.0
    val phase = (tick % trackWindowTicks).toDouble() / trackWindowTicks
    val start = unorm(hash24(seed, PASS_TRACK_EVENT, window, 1))
    val centre = fract(start + TRACK_DRIFT * TRACK_WINDOW_SECONDS * phase)
    val distance = abs(rowCentre(row) - centre)
    val wrapped = min(distance, 1.0 - distance)
    return sin(PI * phase) * (1.0 - smoothstep(0.0, 0.5 * TRACK_BAND, wrapped))
  }

  /**
   * How far [row] slides right at [tick], a fraction of the width.
   */
  public fun rowShift(
    seed: Int,
    tick: Int,
    row: Int,
    params: VhsParams,
  ): Double {
    val seconds = tick / TICK_RATE
    val y = rowCentre(row)
    val phase = unorm(hash24(seed, PASS_WAVE, 0, 0))
    val first = sin(TAU * (WAVE_CYCLES * y + WAVE_HZ * seconds + phase))
    val second = sin(TAU * (WAVE_SECOND_CYCLES * WAVE_CYCLES * y + WAVE_SECOND_HZ * WAVE_HZ * seconds + 2.0 * phase))
    var shift = params.jitter * snorm(hash24(seed, PASS_JITTER, tick, row))
    shift += params.wave * (WAVE_FIRST * first + (1.0 - WAVE_FIRST) * second)
    val intoBand = row - (VIRTUAL_ROWS - headRows)
    if (intoBand >= 0) {
      val wobble = 1.0 + HEAD_WOBBLE * snorm(hash24(seed, PASS_HEAD, tick, 0))
      shift += params.headShift * wobble * ((intoBand + HEAD_OFFSET) / headRows).pow(HEAD_POWER)
    }
    val band = trackingWeight(seed, tick, row, params)
    return shift + params.trackingShift * band * snorm(hash24(seed, PASS_TRACK_SHIFT, tick, row))
  }

  /**
   * Noise along one row from `-1.0` to `1.0`, with cells [cellLength] long, smoothly joined.
   *
   * A phase per row keeps the cell edges from lining up down the picture.
   */
  public fun streak(
    seed: Int,
    passId: Int,
    tick: Int,
    row: Int,
    x: Double,
    cellLength: Double,
  ): Double {
    val u = x / cellLength + unorm(hash24(seed, passId, tick, rowCell(row, 0)))
    val fraction = fract(u)
    val cell = floor(u).toInt()
    val a = snorm(hash24(seed, passId, tick, rowCell(row, cell + 1)))
    val b = snorm(hash24(seed, passId, tick, rowCell(row, cell + 2)))
    return a + (b - a) * (fraction * fraction * (3.0 - 2.0 * fraction))
  }

  /**
   * How much of a dropout covers [x] on [row] at [tick], from 0 to 1.
   */
  public fun dropout(
    seed: Int,
    tick: Int,
    row: Int,
    x: Double,
    params: VhsParams,
  ): Double {
    if (unorm(hash24(seed, PASS_DROP_EVENT, tick, row)) >= params.dropoutChance) return 0.0
    val start = unorm(hash24(seed, PASS_DROP_POS, tick, row))
    val draw = unorm(hash24(seed, PASS_DROP_LEN, tick, row))
    val length = DROPOUT_LENGTH * (DROPOUT_LENGTH_MIN + DROPOUT_LENGTH_SPREAD * draw)
    val into = x - start
    if (into < 0.0 || into > length) return 0.0
    return smoothstep(0.0, DROPOUT_RISE * length, into) * (1.0 - smoothstep(DROPOUT_FALL * length, length, into))
  }

  /**
   * Adds the noise, dropouts and grade to a pixel at [x] on [row] at [tick].
   *
   * Takes and returns full range Y'CbCr, with Cb and Cr centred on 0.
   *
   * @return luma, Cb and Cr, in that order.
   */
  public fun onTape(
    luma: Double,
    cb: Double,
    cr: Double,
    seed: Int,
    tick: Int,
    row: Int,
    x: Double,
    params: VhsParams,
  ): DoubleArray {
    val chromaRow = row / CHROMA_NOISE_ROWS
    val chromaCell = STREAK * CHROMA_COARSENESS
    val band = trackingWeight(seed, tick, row, params)
    val drop = dropout(seed, tick, row, x, params)
    val chromaGain = (1.0 - TRACK_CHROMA_LOSS * band) * (1.0 - drop) * (1.0 - params.desaturation)
    val noisy =
      luma + params.lumaNoise * streak(seed, PASS_LUMA_NOISE, tick, row, x, STREAK) +
        params.trackingNoise * band * streak(seed, PASS_TRACK_NOISE, tick, row, x, TRACK_NOISE_CELL)
    val dropped = noisy + (1.0 - noisy) * drop
    return doubleArrayOf(
      params.lift + dropped * (1.0 - params.lift),
      (cb + params.chromaNoise * streak(seed, PASS_CB_NOISE, tick, chromaRow, x, chromaCell)) * chromaGain,
      (cr + params.chromaNoise * streak(seed, PASS_CR_NOISE, tick, chromaRow, x, chromaCell)) * chromaGain,
    )
  }

  /**
   * The R'G'B' a shader lowering draws at [x] on [row], a pure function a test holds a backend's pixels against.
   *
   * [source] reads the frame's R'G'B' at a fraction of the width, already filtered the way the backend's sampler is.
   * Everything else follows the shader: the shift, the two luma blurs and the chroma blur over a tape that is black
   * past either edge of its [width] pixels, then [onTape] and a clamp.
   */
  public fun pixelAt(
    x: Double,
    row: Int,
    tick: Int,
    seed: Int,
    width: Int,
    params: VhsParams,
    source: (Double) -> DoubleArray,
  ): DoubleArray {
    val shifted = x - rowShift(seed, tick, row, params)
    val tape = { at: Double ->
      val inside = pixelCover(at * width) * pixelCover((1.0 - at) * width)
      yccOf(source(at.coerceIn(0.0, 1.0))).also { ycc -> for (i in ycc.indices) ycc[i] *= inside }
    }
    val luma = { sigma: Double ->
      gaussian(LUMA_TAPS, LUMA_TAP_STEP, sigma, shifted) { tape(it)[0] }
    }
    val sharp = luma(params.lumaSigma)
    val chromaAt = shifted - params.chromaShift
    val cb = gaussian(CHROMA_TAPS, CHROMA_TAP_STEP, params.chromaSigma, chromaAt) { tape(it)[1] }
    val cr = gaussian(CHROMA_TAPS, CHROMA_TAP_STEP, params.chromaSigma, chromaAt) { tape(it)[2] }
    val halo = sharp + params.haloGain * (sharp - luma(params.wideSigma))
    val (y, b, r) = onTape(halo, cb, cr, seed, tick, row, x.coerceIn(0.0, 1.0), params).toList()
    return rgbOf(y, b, r)
  }

  /**
   * The length of a tracking window, in ticks.
   */
  public val trackWindowTicks: Int = floor(TRACK_WINDOW_SECONDS * TICK_RATE + 0.5).toInt()

  /**
   * How many rows the head band covers at the bottom of the virtual raster.
   */
  public val headRows: Int = ceil(HEAD_BAND * VIRTUAL_ROWS).toInt()

  private fun rowCentre(row: Int): Double = (row + 0.5) / VIRTUAL_ROWS

  private fun pixelCover(edge: Double): Double = (edge + 0.5).coerceIn(0.0, 1.0)

  private fun gaussian(
    taps: Int,
    step: Double,
    sigma: Double,
    at: Double,
    read: (Double) -> Double,
  ): Double {
    var sum = read(at)
    for (index in 1..taps) {
      val offset = index * step * sigma
      sum += tapWeight(index, step) * (read(at + offset) + read(at - offset))
    }
    return sum / tapSum(taps, step)
  }

  private fun yccOf(rgb: DoubleArray): DoubleArray {
    val y = KR * rgb[0] + (1.0 - KR - KB) * rgb[1] + KB * rgb[2]
    return doubleArrayOf(y, (rgb[2] - y) / (2.0 * (1.0 - KB)), (rgb[0] - y) / (2.0 * (1.0 - KR)))
  }

  private fun rgbOf(
    y: Double,
    cb: Double,
    cr: Double,
  ): DoubleArray {
    val r = y + 2.0 * (1.0 - KR) * cr
    val b = y + 2.0 * (1.0 - KB) * cb
    val g = (y - KR * r - KB * b) / (1.0 - KR - KB)
    return doubleArrayOf(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
  }

  private fun rowCell(
    row: Int,
    cell: Int,
  ): Int = row + cell * ROW_CELL_STRIDE

  private fun fract(value: Double): Double = value - floor(value)
}
