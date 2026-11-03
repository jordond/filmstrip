package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.color.BT2020_CHROMA_SHIFT
import dev.jordond.filmstrip.color.BT2020_DECODE_ROWS
import dev.jordond.filmstrip.color.BT2020_ENCODE_ROWS
import dev.jordond.filmstrip.color.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.color.TEN_BIT_CHROMA_MID
import dev.jordond.filmstrip.color.TEN_BIT_CHROMA_RANGE
import dev.jordond.filmstrip.color.TEN_BIT_LUMA_FLOOR
import dev.jordond.filmstrip.color.TEN_BIT_LUMA_RANGE
import dev.jordond.filmstrip.color.TEN_BIT_MAX_CODE
import dev.jordond.filmstrip.color.TEN_TO_SIXTEEN_BITS
import dev.jordond.filmstrip.color.hlgDisplayNitsFromSignal
import dev.jordond.filmstrip.color.hlgSignalFromDisplayNits
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.color.peakNits
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.sceneFromHlgSignal
import dev.jordond.filmstrip.color.sdrSignalCeiling
import dev.jordond.filmstrip.color.sdrSignalCeilingDouble
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.isDiagonal
import dev.jordond.filmstrip.media.HdrTransfer
import java.util.Formatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// The tables and mixer rows a color effect or an overlay on a kept HDR grade runs through. Every entry is worked out
// here in double from core's BT.2020 rows, constants and transfer functions, and ffmpeg holds it as a float.

/**
 * The most entries ffmpeg's lut1d holds a channel, its own `MAX_1D_LEVEL`.
 *
 * One more than the largest sixteen-bit sample, so a table this size has an entry for every sample the lanes put on a
 * float plane and a nearest lookup lands on the sample's own entry.
 */
internal const val MAX_1D_LEVEL: Int = 65536

/**
 * A table for ffmpeg's lut1d, one column each for the red, green and blue planes.
 *
 * Entry `i` of a column is what an input of `i / (size - 1)` reads. lut1d clamps its input to the range zero to one
 * first, so a table also floors a negative input and caps one past the top.
 */
internal class LutTable(
  val columns: List<DoubleArray>,
) {
  /**
   * The table as a `.cube` file, each entry printed to nine significant digits, which is all a float holds.
   *
   * Written once and kept, since the tables a grade shares are each read by every grade on the same transfer.
   */
  val sidecar: Sidecar by lazy { Sidecar(cube1d(columns), CUBE_EXTENSION) }
}

/**
 * A matrix that mixes the three planes, and the bias each row adds after it.
 *
 * Runs as colorchannelmixer when [bias] is null and as a two-point lut3d when it is not, since colorchannelmixer has no
 * bias of its own. A two-point lut3d holds an affine map exactly.
 *
 * @property rows Red, green and blue out, each a row of red, green and blue in.
 */
internal class PlaneMix(
  val rows: List<DoubleArray>,
  val bias: DoubleArray?,
) {
  /**
   * The eight corners of the unit cube as a `.cube` file, red varying fastest, printed the way [LutTable] prints.
   *
   * Each corner is the rows applied to it plus the bias, so lut3d's interpolation between them is the affine map.
   */
  val sidecar: Sidecar by lazy {
    Sidecar(cube3d(rows, checkNotNull(bias) { "A mix with no bias runs as a mixer." }), CUBE_EXTENSION)
  }
}

/**
 * The tables one color [matrix] on a [transfer] grade runs through, in the order the graph runs them.
 *
 * The grade's planes hold its codes as sixteen-bit fractions, luma on green, Cb on blue and Cr on red. [CODE_CENTRES]
 * takes each code to its distance from black or the chroma middle, and [DECODE_ROWS] mixes those into the R'G'B'
 * signal. [decodeWarp] and [curve] take each channel to its scaled SDR signal, the SDR signal its light would have
 * been at reference white divided by the transfer's ceiling. The matrix runs there, folded into [curve] when it is
 * diagonal and in [mix] when it is not, scaled down by [span] so every output fits the tables after it.
 * [encodeWarp] and [encode] take each channel back to the signal, shifted so [ENCODE_ROWS] writes each code's distance
 * from black, and [CODE_STEPS] rounds that to the whole code as a sixteen-bit fraction.
 *
 * A warp spreads the first stretch of its input over many more entries than it would get evenly, so the next table
 * resolves the steep end of the transfer curve. Both are piecewise linear with their knee on an entry, so the warp
 * itself interpolates exactly.
 */
internal class HdrGradeTables(
  matrix: ColorMatrix,
  transfer: HdrTransfer,
) {
  private val weights =
    listOf(
      doubleArrayOf(matrix.rr.toDouble(), matrix.rg.toDouble(), matrix.rb.toDouble()),
      doubleArrayOf(matrix.gr.toDouble(), matrix.gg.toDouble(), matrix.gb.toDouble()),
      doubleArrayOf(matrix.br.toDouble(), matrix.bg.toDouble(), matrix.bb.toDouble()),
    )

  // The bias is divided by the ceiling in float, the way the shared grade scales it.
  private val bias =
    floatArrayOf(matrix.rBias, matrix.gBias, matrix.bBias).map { (it / transfer.sdrSignalCeiling).toDouble() }

  // The input node that the scaled signal one lands on. A mix whose outputs reach past one, or whose weights reach
  // past what colorchannelmixer takes, is scaled down until they fit, and the scale is then rounded so one still sits
  // on a node.
  private val top: Int =
    floor(LAST_NODE / maxOf(1.0, reach(), weights.maxOf { row -> row.maxOf { abs(it) } } / MIXER_GAIN)).toInt()

  /**
   * What the matrix's outputs are divided by so the largest it writes is at most one.
   *
   * One for most matrices. A span past one puts a scaled signal of one on a node below the last, and [encodeWarp] reads
   * it there.
   */
  val span: Double = LAST_NODE.toDouble() / top

  /**
   * The warp a PQ signal goes through ahead of [curve], or null on HLG, whose curve is shallow enough without one.
   *
   * It is the same table on every PQ grade.
   */
  val decodeWarp: LutTable? = if (transfer == HdrTransfer.Pq) DECODE_WARP_TABLE else null

  /**
   * Each channel's scaled SDR signal, with the matrix folded in when it is diagonal.
   *
   * A mix reads the curve its transfer shares with every other mix and runs its matrix in [mix] after it.
   */
  val curve: LutTable

  /**
   * The matrix scaled by [span], or null when it is diagonal and [curve] holds it.
   *
   * Its bias is scaled by [span] too, and is null when the matrix has none.
   */
  val mix: PlaneMix?

  /**
   * The warp a scaled signal goes through ahead of [encode].
   *
   * It reads a scaled signal of one on the node [span] leaves it on, and every matrix whose span is one shares it.
   */
  val encodeWarp: LutTable

  /**
   * Each channel's signal, shifted by its share of the chroma offset.
   *
   * The shift is core's [BT2020_CHROMA_SHIFT], which lets [ENCODE_ROWS] write every code as its distance from black.
   */
  val encode: LutTable

  init {
    val warp = if (transfer == HdrTransfer.Pq) DECODE_WARP else null
    if (matrix.isDiagonal) {
      val decoded = decodeCurve(transfer, warp)
      curve = LutTable(List(3) { c -> DoubleArray(MAX_1D_LEVEL) { (weights[c][c] * decoded[it] + bias[c]) / span } })
      mix = null
    } else {
      curve = decodeTables.getValue(transfer)
      val rows = weights.map { row -> DoubleArray(3) { row[it] / span } }
      mix = PlaneMix(rows, if (bias.all { it == 0.0 }) null else DoubleArray(3) { bias[it] / span })
    }
    val (warpTable, encodeTable) =
      if (top == LAST_NODE) sharedEncodeTables.getValue(transfer) else encodeTablesFor(transfer, top)
    encodeWarp = warpTable
    encode = encodeTable
  }

  // The most a row can write for channels between zero and one: its positive weights and a positive bias.
  private fun reach(): Double =
    weights.indices.maxOf { row -> weights[row].sumOf { maxOf(it, 0.0) } + maxOf(bias[row], 0.0) }

  companion object {
    /**
     * Each sixteen-bit sample's code, as its distance from the chroma middle on red and blue and from black on green,
     * each over the range it spans.
     *
     * Read with nearest sampling, so the lanes' float plane picks out the sample it came from.
     */
    val CODE_CENTRES: LutTable by lazy {
      val codes = DoubleArray(MAX_1D_LEVEL) { it.toDouble() / TEN_TO_SIXTEEN_BITS }
      val luma = DoubleArray(MAX_1D_LEVEL) { (codes[it] - TEN_BIT_LUMA_FLOOR) / TEN_BIT_LUMA_RANGE }
      val chroma = DoubleArray(MAX_1D_LEVEL) { (codes[it] - TEN_BIT_CHROMA_MID) / TEN_BIT_CHROMA_RANGE }
      LutTable(LANE_ROWS.map { if (it == LUMA_ROW) luma else chroma })
    }

    /**
     * Core's BT.2020 decode rows from [CODE_CENTRES]' Cr, luma and Cb to red, green and blue signal.
     *
     * Each row is core's row for that output with its columns put in the order the lanes leave the planes in.
     */
    val DECODE_ROWS: List<DoubleArray> =
      List(3) { out -> DoubleArray(3) { lane -> BT2020_DECODE_ROWS[out][LANE_ROWS[lane]] } }

    /**
     * Core's BT.2020 encode rows from red, green and blue signal to Cr, luma and Cb, each as its code's distance from
     * its own offset over the steps of [CODE_STEPS].
     *
     * Each row is core's row for the plane that lane carries, scaled from its signal span to the codes it covers.
     */
    val ENCODE_ROWS: List<DoubleArray> =
      LANE_ROWS.map { row ->
        val range = if (row == LUMA_ROW) TEN_BIT_LUMA_RANGE else TEN_BIT_CHROMA_RANGE
        DoubleArray(3) { BT2020_ENCODE_ROWS[row][it] * range / TEN_BIT_MAX_CODE }
      }

    /**
     * Each ten-bit code's distance from black, as an entry, to the code's sixteen-bit fraction.
     *
     * Read with nearest sampling, which rounds to the whole code. A code past the top lands on the top.
     */
    val CODE_STEPS: LutTable by lazy {
      val steps =
        DoubleArray(TEN_BIT_MAX_CODE + 1) { minOf(it + TEN_BIT_LUMA_FLOOR, TEN_BIT_MAX_CODE) * SIXTEEN_PER_TEN }
      LutTable(List(3) { steps })
    }

    /**
     * The warp a signal goes through ahead of [lightCurve], on either transfer.
     *
     * Light climbs steeply off black on both, PQ's from its black level and HLG's as the square of the signal, so this
     * is the warp a PQ signal takes ahead of a color effect's curve too.
     */
    val LIGHT_WARP: LutTable get() = DECODE_WARP_TABLE

    /**
     * Each channel's linear light on a [transfer] grade, as [linearFromSignal] decodes it, read through [LIGHT_WARP].
     *
     * Worked out once per transfer and shared by every overlay on it.
     */
    fun lightCurve(transfer: HdrTransfer): LutTable = lightTables.getValue(transfer)

    private val DECODE_WARP = SegmentWarp(DECODE_KNEE, WARP_STRETCH, LAST_NODE)
    private val DECODE_WARP_TABLE by lazy { DECODE_WARP.table() }

    private val decodeTables: Map<HdrTransfer, LutTable> by lazy {
      HdrTransfer.entries.associateWith { transfer ->
        val decoded = decodeCurve(transfer, if (transfer == HdrTransfer.Pq) DECODE_WARP else null)
        LutTable(List(3) { decoded })
      }
    }

    private val lightTables: Map<HdrTransfer, LutTable> by lazy {
      HdrTransfer.entries.associateWith { transfer ->
        val light = DoubleArray(MAX_1D_LEVEL) { linearFromSignal(transfer, DECODE_WARP.inputAt(it)) }
        LutTable(List(3) { light })
      }
    }

    // Most matrices write nothing past one, so their encode tables are shared.
    private val sharedEncodeTables: Map<HdrTransfer, Pair<LutTable, LutTable>> by lazy {
      HdrTransfer.entries.associateWith { encodeTablesFor(it, LAST_NODE) }
    }
  }
}

/**
 * Decodes one channel of a [transfer] [signal] to its scaled SDR signal: the SDR signal its light would have been at
 * reference white, divided by the transfer's ceiling so the transfer's peak reads as one.
 *
 * PQ's signal decodes straight to display light, and HLG's through core's per-channel display decode. Both run on
 * core's double transfers.
 */
internal fun scaledSdrFromSignal(
  transfer: HdrTransfer,
  signal: Double,
): Double {
  val sdr =
    when (transfer) {
      HdrTransfer.Pq -> sdrSignalFromNits(nitsFromPqSignal(signal))
      HdrTransfer.Hlg -> sdrSignalFromNits(hlgDisplayNitsFromSignal(signal))
    }
  return sdr / transfer.sdrSignalCeilingDouble
}

/**
 * Decodes one channel of a [transfer] [signal] to linear light on its own, as a fraction of the transfer's peak.
 *
 * On PQ that is display light. On HLG it is scene light, which reaches display light through a gain worked out from
 * the luminance of all three channels, so the gain runs after this on the whole pixel.
 */
internal fun linearFromSignal(
  transfer: HdrTransfer,
  signal: Double,
): Double =
  when (transfer) {
    HdrTransfer.Pq -> nitsFromPqSignal(signal) / transfer.peakNits
    HdrTransfer.Hlg -> sceneFromHlgSignal(signal)
  }

/**
 * Encodes one channel of a [scaled] SDR signal, as [scaledSdrFromSignal] decodes it, back onto [transfer]'s signal.
 *
 * The scaled signal is clamped at one, which is the transfer's peak and where the format runs out, so the result lies
 * in the range zero to one.
 */
internal fun signalFromScaledSdr(
  transfer: HdrTransfer,
  scaled: Double,
): Double {
  val nits = nitsFromSdrSignal(scaled.coerceIn(0.0, 1.0) * transfer.sdrSignalCeilingDouble)
  return when (transfer) {
    HdrTransfer.Pq -> pqSignalFromNits(nits)
    HdrTransfer.Hlg -> hlgSignalFromDisplayNits(nits)
  }
}

/**
 * A piecewise-linear warp over the nodes of a [MAX_1D_LEVEL] table, input node [top] being an input of one.
 *
 * The first [knee] of the input is stretched [stretch] times over the output, and the rest runs on a line to the last
 * node. Both ends of each piece sit on a node, so lut1d interpolates the warp exactly and only the float it is held in
 * rounds. Any input past [top] lands on the last node.
 */
internal class SegmentWarp(
  knee: Double,
  stretch: Double,
  private val top: Int,
) {
  private val kneeIn = (knee * top).roundToInt().coerceAtLeast(1)
  private val kneeOut = (stretch * kneeIn).roundToInt().coerceAtMost(LAST_NODE - 1)

  /**
   * The warp as a table, the same for all three planes.
   *
   * Entry `n` is where the warp takes an input on node `n`, as a fraction of the last node.
   */
  fun table(): LutTable {
    val out =
      DoubleArray(MAX_1D_LEVEL) { node ->
        when {
          node <= kneeIn -> node.toDouble() * kneeOut / kneeIn
          node > top -> LAST_NODE.toDouble()
          else -> kneeOut + (LAST_NODE - kneeOut).toDouble() * (node - kneeIn) / (top - kneeIn)
        } / LAST_NODE
      }
    return LutTable(List(3) { out })
  }

  /**
   * The input, where one sits on [top], that the warp takes to output [node].
   *
   * This is the inverse of [table], and a table read after the warp is built on it.
   */
  fun inputAt(node: Int): Double {
    val at =
      if (node <= kneeOut) {
        node.toDouble() * kneeIn / kneeOut
      } else {
        kneeIn + (node - kneeOut).toDouble() * (top - kneeIn) / (LAST_NODE - kneeOut)
      }
    return at / top
  }
}

// Each node's scaled SDR signal, where the node's signal is the one the warp takes to it, or the node's own fraction.
private fun decodeCurve(
  transfer: HdrTransfer,
  warp: SegmentWarp?,
): DoubleArray =
  DoubleArray(MAX_1D_LEVEL) { node ->
    scaledSdrFromSignal(transfer, warp?.inputAt(node) ?: (node.toDouble() / LAST_NODE))
  }

// The encode warp for a mix whose scaled one sits on node [top], and the curve after it.
private fun encodeTablesFor(
  transfer: HdrTransfer,
  top: Int,
): Pair<LutTable, LutTable> {
  val warp = SegmentWarp(ENCODE_KNEE, WARP_STRETCH, top)
  val signal = DoubleArray(MAX_1D_LEVEL) { signalFromScaledSdr(transfer, warp.inputAt(it)) }
  return warp.table() to LutTable(List(3) { c -> DoubleArray(MAX_1D_LEVEL) { signal[it] + BT2020_CHROMA_SHIFT[c] } })
}

private fun cube1d(columns: List<DoubleArray>): ByteArray {
  val text = StringBuilder(columns[0].size * CUBE_ROW_CHARS).append("LUT_1D_SIZE ${columns[0].size}\n")
  val formatter = Formatter(text, Locale.ROOT)
  columns[0].indices.forEach { formatter.format(CUBE_ROW, columns[0][it], columns[1][it], columns[2][it]) }
  return text.toString().encodeToByteArray()
}

private fun cube3d(
  rows: List<DoubleArray>,
  bias: DoubleArray,
): ByteArray {
  val text = StringBuilder("LUT_3D_SIZE 2\n")
  val formatter = Formatter(text, Locale.ROOT)
  for (blue in 0..1) {
    for (green in 0..1) {
      for (red in 0..1) {
        val out = DoubleArray(3) { rows[it][0] * red + rows[it][1] * green + rows[it][2] * blue + bias[it] }
        formatter.format(CUBE_ROW, out[0], out[1], out[2])
      }
    }
  }
  return text.toString().encodeToByteArray()
}

// Core indexes Y'CbCr as luma, Cb and Cr, the order of PlaneLanes.YCBCR_SLOTS, and the lanes put each of those planes
// in the slot RGB_SLOTS gives the same place. So the Y'CbCr index the red, green or blue plane carries is its slot.
private val LANE_ROWS: List<Int> = listOf("r", "g", "b").map { PlaneLanes.RGB_SLOTS.indexOf(it) }

private val LUMA_ROW: Int = PlaneLanes.YCBCR_SLOTS.indexOf("y")

// The last node of a MAX_1D_LEVEL table, which an input of one reads.
private const val LAST_NODE: Int = MAX_1D_LEVEL - 1

// A ten-bit code's sixteen-bit sample as a fraction of full scale, per code.
private const val SIXTEEN_PER_TEN = TEN_TO_SIXTEEN_BITS.toDouble() / SIXTEEN_BIT_MAX

// colorchannelmixer refuses a gain past two, its own limit.
private const val MIXER_GAIN = 2.0

// How much of the input each warp stretches, and by how much. A PQ signal's light climbs steeply off black, and so does
// a signal off a scaled SDR signal near black.
private const val DECODE_KNEE = 0.0002
private const val ENCODE_KNEE = 0.001
private const val WARP_STRETCH = 100.0

private const val CUBE_EXTENSION = "cube"
private const val CUBE_ROW = "%.9g %.9g %.9g\n"
private const val CUBE_ROW_CHARS = 48
