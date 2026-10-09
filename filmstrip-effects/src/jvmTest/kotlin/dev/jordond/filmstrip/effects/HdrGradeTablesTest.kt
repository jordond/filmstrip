package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.media.BT2020_CHROMA_SHIFT
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.TEN_BIT_LUMA_RANGE
import dev.jordond.filmstrip.media.TEN_TO_SIXTEEN_BITS
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.hlgDisplayNitsFromSignal
import dev.jordond.filmstrip.media.hlgSignalFromDisplayNits
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.nitsFromPqSignal
import dev.jordond.filmstrip.media.nitsFromSdrSignal
import dev.jordond.filmstrip.media.peakNits
import dev.jordond.filmstrip.media.pqSignalFromNits
import dev.jordond.filmstrip.media.sdrSignalCeiling
import dev.jordond.filmstrip.media.sdrSignalFromNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.signalFromTenBitCodes
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tables and mixer rows a colour effect on a kept HDR grade runs through, read here the way ffmpeg's lut1d and
 * colorchannelmixer read them and held against core's own figures.
 *
 * Every level is read in the middle of its range, where a gain read as additive and one read as multiplicative part
 * ways, as well as at the transfer's peak.
 */
class HdrGradeTablesTest {
  @Test
  fun `a signal decodes to the scaled SDR signal core reads its light as`() {
    for (transfer in HdrTransfer.entries) {
      val nits =
        when (transfer) {
          HdrTransfer.Pq -> nitsFromPqSignal(MID_SIGNAL)
          HdrTransfer.Hlg -> hlgDisplayNitsFromSignal(MID_SIGNAL)
        }
      val read = scaledSdrFromSignal(transfer, MID_SIGNAL.toDouble())

      assertNear(read, sdrSignalFromNits(nits) / transfer.sdrSignalCeiling, "$transfer $MID_SIGNAL")
    }
  }

  // Light climbs steeply off black on both transfers, so a signal a code above black is read as well as the middle.
  @Test
  fun `a signal reads through the light warp and curve as the light core decodes it to`() {
    for (transfer in HdrTransfer.entries) {
      for (signal in listOf(NEAR_BLACK_SIGNAL, MID_SIGNAL)) {
        val warped = HdrGradeTables.LIGHT_WARP.linear(DoubleArray(3) { signal.toDouble() })
        val light = HdrGradeTables.lightCurve(transfer).linear(warped)

        for (channel in 0..2) {
          val read =
            when (transfer) {
              HdrTransfer.Pq -> pqSignalFromNits(light[channel].toFloat() * transfer.peakNits)
              HdrTransfer.Hlg -> hlgSignalFromScene(light[channel].toFloat())
            }
          assertNear(read.toDouble(), signal, "$transfer $signal")
        }
      }
    }
  }

  // A scaled signal past one asks for more light than the transfer's peak, so it lands on the top of the signal.
  @Test
  fun `a scaled SDR signal encodes onto the signal core writes its light at`() {
    for (transfer in HdrTransfer.entries) {
      val nits = nitsFromSdrSignal(MID_SCALED * transfer.sdrSignalCeiling)

      assertNear(signalFromScaledSdr(transfer, MID_SCALED.toDouble()), signalOf(transfer, nits), "$transfer mid")
      assertNear(signalFromScaledSdr(transfer, PAST_PEAK), 1f, "$transfer past the peak")
    }
  }

  @Test
  fun `the codes decode to the signal core's matrix decodes them to`() {
    for (codes in GRID_CODES) {
      val signal = mixed(HdrGradeTables.DECODE_ROWS, HdrGradeTables.CODE_CENTRES.nearest(planesOf(codes)))
      val expected = signalFromTenBitCodes(codes[0], codes[1], codes[2])

      for (channel in 0..2) assertNear(signal[channel].coerceIn(0.0, 1.0), expected[channel], "${codes.toList()}")
    }
  }

  // The signal is shifted by the chroma offset on the way in, so the rows land each code a whole number of steps from
  // black and the step table rounds it onto the code core writes, as a sixteen-bit fraction.
  @Test
  fun `a signal encodes to the codes core's matrix rounds it to`() {
    for (codes in GRID_CODES) {
      val signal = signalFromTenBitCodes(codes[0], codes[1], codes[2])
      val shifted = DoubleArray(3) { signal[it] + BT2020_CHROMA_SHIFT[it] }
      val written = HdrGradeTables.CODE_STEPS.nearest(mixed(HdrGradeTables.ENCODE_ROWS, shifted))
      val expected = tenBitCodesFromSignal(signal)

      assertEquals(
        listOf(expected[2], expected[0], expected[1]),
        written.map { (it * SIXTEEN_BIT_MAX / TEN_TO_SIXTEEN_BITS).roundToInt() },
        "${codes.toList()}",
      )
    }
  }

  // Every matrix shape: diagonal ones folded into the curve, mixes without a bias on the mixer and one with a bias on
  // the two-point table.
  @Test
  fun `each colour effect walked through its tables lands on the signal the shared grade writes`() {
    for (transfer in HdrTransfer.entries) {
      for (matrix in MATRICES) {
        val tables = HdrGradeTables(matrix, transfer)
        for (colour in COLOURS) {
          val codes = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(colour)))
          val read = walked(tables, codes)
          val expected = sharedGrade(matrix, transfer, codes)

          for (channel in 0..2) assertNear(read[channel], expected[channel], "$transfer $matrix ${codes.toList()}")
        }
      }
    }
  }

  @Test
  fun `a diagonal matrix folds into the curve and a mix keeps its bias off the mixer`() {
    val transfer = HdrTransfer.Pq
    val biased = HdrGradeTables(MATRICES.last(), transfer).mix

    assertNull(HdrGradeTables(checkNotNull(colorMatrixOf(Contrast(MID_FACTOR))), transfer).mix)
    assertNull(HdrGradeTables(checkNotNull(colorMatrixOf(Saturation(MID_FACTOR))), transfer).mix?.bias)
    assertTrue(biased?.bias != null, "a mix with a bias runs on the two-point table")
  }

  // colorchannelmixer refuses a gain past two, so a mix that reaches past it is scaled down, and the tables after it
  // read the scaled signal back.
  @Test
  fun `no mixer row asks colorchannelmixer for a gain past two`() {
    val steep = ColorMatrix(rr = 0.5f, rg = -STEEP, rb = STEEP, gg = 0.5f, bb = 0.5f)
    val rows =
      listOf(steep, checkNotNull(colorMatrixOf(Saturation(WIDE_SATURATION)))).flatMap {
        checkNotNull(HdrGradeTables(it, HdrTransfer.Pq).mix).rows
      } + HdrGradeTables.DECODE_ROWS + HdrGradeTables.ENCODE_ROWS

    assertTrue(rows.all { row -> row.all { abs(it) <= MIXER_GAIN } }, rows.map { it.toList() }.toString())
  }

  // ffmpeg's lut1d holds at most MAX_1D_LEVEL entries, and that is one per sixteen-bit sample, so the nearest read off
  // a lanes plane picks out its own sample.
  @Test
  fun `every sixteen-bit table fills lut1d's MAX_1D_LEVEL entries`() {
    val tables = HdrGradeTables(checkNotNull(colorMatrixOf(Brightness(MID_FACTOR))), HdrTransfer.Pq)
    val all =
      listOfNotNull(HdrGradeTables.CODE_CENTRES, tables.decodeWarp, tables.curve, tables.encodeWarp, tables.encode) +
        HdrGradeTables.LIGHT_WARP + HdrTransfer.entries.map { HdrGradeTables.lightCurve(it) }

    assertEquals(SIXTEEN_BIT_MAX + 1, MAX_1D_LEVEL)
    for (table in all) {
      assertEquals("LUT_1D_SIZE $MAX_1D_LEVEL", table.cubeLines().first())
      assertTrue(table.columns.all { it.size == MAX_1D_LEVEL }, "a column of $MAX_1D_LEVEL entries")
    }
  }

  @Test
  fun `a table prints each entry to nine significant digits`() {
    val table = HdrGradeTables(checkNotNull(colorMatrixOf(Brightness(MID_FACTOR))), HdrTransfer.Hlg).curve
    val node = MAX_1D_LEVEL / 2
    val row = table.cubeLines()[node + 1]

    assertEquals(table.columns.joinToString(" ") { String.format(Locale.ROOT, "%.9g", it[node]) }, row)
  }

  // The grade core writes: each channel's light through the matrix on its SDR signal, back onto the transfer's signal.
  private fun sharedGrade(
    matrix: ColorMatrix,
    transfer: HdrTransfer,
    codes: IntArray,
  ): FloatArray {
    val signal = signalFromTenBitCodes(codes[0], codes[1], codes[2])
    val nits =
      FloatArray(3) {
        when (transfer) {
          HdrTransfer.Pq -> nitsFromPqSignal(signal[it])
          HdrTransfer.Hlg -> hlgDisplayNitsFromSignal(signal[it])
        }
      }
    val graded = matrix.transformNits(nits[0], nits[1], nits[2], transfer)
    return FloatArray(3) { signalOf(transfer, graded[it]) }
  }

  private fun signalOf(
    transfer: HdrTransfer,
    nits: Float,
  ): Float =
    when (transfer) {
      HdrTransfer.Pq -> pqSignalFromNits(nits)
      HdrTransfer.Hlg -> hlgSignalFromDisplayNits(nits)
    }

  // The signal a pixel of [codes] leaves the encode table with, its chroma shift taken back off.
  private fun walked(
    tables: HdrGradeTables,
    codes: IntArray,
  ): DoubleArray {
    var rgb = mixed(HdrGradeTables.DECODE_ROWS, HdrGradeTables.CODE_CENTRES.nearest(planesOf(codes)))
    tables.decodeWarp?.let { rgb = it.linear(rgb) }
    rgb = tables.curve.linear(rgb)
    tables.mix?.let { mix ->
      val bias = mix.bias ?: DoubleArray(3)
      rgb = mixed(mix.rows, rgb).mapIndexed { channel, value -> value + bias[channel] }.toDoubleArray()
    }
    rgb = tables.encode.linear(tables.encodeWarp.linear(rgb))
    return DoubleArray(3) { rgb[it] - BT2020_CHROMA_SHIFT[it] }
  }

  // Cr, luma and Cb as the lanes leave them on the red, green and blue planes, each code's sixteen-bit fraction.
  private fun planesOf(codes: IntArray): DoubleArray =
    DoubleArray(3) { codes[CODE_ON_PLANE[it]].toDouble() * TEN_TO_SIXTEEN_BITS / SIXTEEN_BIT_MAX }

  private fun LutTable.cubeLines(): List<String> = sidecar.bytes.decodeToString().lines()

  private fun mixed(
    rows: List<DoubleArray>,
    rgb: DoubleArray,
  ): DoubleArray = DoubleArray(3) { out -> rows[out].indices.sumOf { rows[out][it] * rgb[it] } }

  // lut1d's reads: the input clamped to the range zero to one and scaled onto the entries, then the entry nearest it or
  // the line between the two either side of it.
  private fun LutTable.nearest(rgb: DoubleArray): DoubleArray =
    DoubleArray(3) { columns[it][(at(rgb[it], columns[it].size) + HALF).toInt()] }

  private fun LutTable.linear(rgb: DoubleArray): DoubleArray =
    DoubleArray(3) {
      val column = columns[it]
      val at = at(rgb[it], column.size)
      val below = at.toInt()
      val above = minOf(below + 1, column.lastIndex)
      column[below] + (column[above] - column[below]) * (at - below)
    }

  private fun at(
    input: Double,
    size: Int,
  ): Double = input.coerceIn(0.0, 1.0) * (size - 1)

  private fun assertNear(
    read: Double,
    expected: Float,
    what: String,
  ) {
    assertTrue(abs(read - expected) <= TOLERANCE, "$what read $read, expected $expected")
  }

  private companion object {
    const val MID_SIGNAL = 0.6f

    // One ten-bit code above black, as a signal.
    const val NEAR_BLACK_SIGNAL = 1f / TEN_BIT_LUMA_RANGE

    // A scaled SDR signal in the middle of the range and one past the transfer's peak.
    const val MID_SCALED = 0.3f
    const val PAST_PEAK = 1.5

    // Which of luma, Cb and Cr each of the red, green and blue planes holds.
    val CODE_ON_PLANE = intArrayOf(2, 0, 1)

    const val MID_FACTOR = 0.5f
    const val STEEP = 3f
    const val WIDE_SATURATION = 2.5f
    const val MIXER_GAIN = 2.0
    const val HALF = 0.5

    // White, a mid grey and a saturated green, where a figure read per channel and one read on luminance part ways.
    val COLOURS = listOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF308020.toInt())

    val MATRICES: List<ColorMatrix> =
      listOf(Brightness(MID_FACTOR), Brightness(1.5f), Contrast(1.5f), Saturation(MID_FACTOR), HueRotate(90f))
        .map { checkNotNull(colorMatrixOf(it)) } +
        ColorMatrix(rr = 0.8f, rg = 0.2f, rBias = 0.1f, gg = 0.9f, bb = 1.1f, bBias = -0.05f)

    // Luma, Cb and Cr codes across video range, chroma either side of its middle.
    val GRID_CODES: List<IntArray> =
      listOf(intArrayOf(64, 512, 512), intArrayOf(300, 420, 600), intArrayOf(502, 610, 455), intArrayOf(940, 512, 512))

    // A hundredth of a ten-bit code, which is about a hundred-thousandth of the signal.
    const val TOLERANCE = 1e-5
  }
}
