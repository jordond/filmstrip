package dev.jordond.filmstrip.color.icc

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.color.displayLightCeiling
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.sceneFromHlgSignal
import dev.jordond.filmstrip.color.srgbToLinear
import kotlin.math.abs
import kotlin.math.pow

// An ICC profile read down to what an RGB or grey picture needs, the color each stored texel stands for. Every
// color comes out as XYZ in the profile connection space, which the ICC spec fixes at D50. A matrix and curves
// profile, a grey profile and an `A2B1` table give the relative colorimetric color, and an `A2B0` table, which holds
// the perceptual one, stands in for it when the profile has no `A2B1`.

/**
 * The color an RGB or grey picture's stored channels stand for, as its embedded ICC profile defines it.
 *
 * Built by [parseIccProfile] from a matrix and three tone curves, from an `A2B1` or `A2B0` lookup table, or from a
 * grey tone curve.
 */
internal sealed interface IccProfile {
  /**
   * Converts encoded channels in the range zero to one to XYZ in the D50 connection space, where the profile's white
   * has a Y of one.
   *
   * The color is relative colorimetric, read from an `A2B1` table when the profile has one. An `A2B0` table, which
   * holds the perceptual color, stands in for it when that is the only table.
   *
   * A grey profile reads [green], since a grey decode stores its one level on all three channels.
   */
  fun toPcs(
    red: Double,
    green: Double,
    blue: Double,
  ): DoubleArray

  /**
   * Writes the XYZ [toPcs] gives for the eight bit codes packed in [rgb] into [pcs], red in bits 16 to 23, green in
   * bits 8 to 15 and blue in the low byte.
   *
   * A matrix and curves profile and a grey profile read their curves from tables of all 256 codes, built once with the
   * profile, so they evaluate no curve here.
   */
  fun codesToPcs(
    rgb: Int,
    pcs: DoubleArray,
  ) {
    toPcs(codeLevel(rgb, RED_SHIFT), codeLevel(rgb, GREEN_SHIFT), codeLevel(rgb, 0)).copyInto(pcs)
  }

  /**
   * Builds a reader that writes the XYZ [toPcs] gives for sixteen-bit samples, each read as the sample over 65535.
   *
   * When [tabulate] holds, a matrix and curves profile and a grey profile read their curves from tables of all 65536
   * samples, built here and shared by channels with the same curve, so the reader evaluates no curve. A lookup table
   * profile reads through [toPcs] either way.
   */
  fun sampleReader(tabulate: Boolean): SampleReader =
    SampleReader { red, green, blue, pcs ->
      toPcs(red / MAX_SAMPLE, green / MAX_SAMPLE, blue / MAX_SAMPLE).copyInto(pcs)
    }

  /**
   * Whether this is sRGB at the precision the profile format stores it at.
   *
   * True for a matrix and curves profile whose colorants sit within one s15Fixed16 step of sRGB's and whose curves
   * sit within one sixteen-bit step of the sRGB curve at every sample.
   */
  val isSrgb: Boolean
}

/**
 * Writes the connection space XYZ of one color given as sixteen-bit samples, each in the range zero to 65535, into
 * the array it is handed.
 */
internal fun interface SampleReader {
  fun read(
    red: Int,
    green: Int,
    blue: Int,
    pcs: DoubleArray,
  )
}

/**
 * A tone curve from a `curv` or `para` tag, taking an encoded channel to linear light, both in the range zero to one.
 *
 * [Pq] and [HlgScene] stand for the HDR transfers a CICP description names. [Pq] reaches past one, since its light is
 * relative to [HDR_REFERENCE_WHITE_NITS] rather than to its peak.
 */
internal sealed interface IccCurve {
  fun evaluate(x: Double): Double

  data object Identity : IccCurve {
    override fun evaluate(x: Double): Double = x.coerceIn(0.0, 1.0)
  }

  /**
   * The sRGB curve, evaluated through [srgbToLinear] so a description that names sRGB's transfer reads every level
   * exactly where an untagged picture's does.
   */
  data object Srgb : IccCurve {
    override fun evaluate(x: Double): Double = srgbToLinear(x.toFloat()).toDouble()
  }

  /**
   * The PQ curve, decoding a signal through [nitsFromPqSignal] to light where one is [HDR_REFERENCE_WHITE_NITS], so
   * its peak lands at [HdrTransfer.displayLightCeiling].
   */
  data object Pq : IccCurve {
    override fun evaluate(x: Double): Double = nitsFromPqSignal(x) / HDR_REFERENCE_WHITE_NITS
  }

  /**
   * The HLG curve, decoding a signal through [sceneFromHlgSignal] to scene light where one is the nominal peak.
   *
   * It stops short of display light, since the opto-optical transfer after it reads all three channels at once.
   */
  data object HlgScene : IccCurve {
    override fun evaluate(x: Double): Double = sceneFromHlgSignal(x)
  }

  class Gamma(
    private val gamma: Double,
  ) : IccCurve {
    override fun evaluate(x: Double): Double = x.coerceIn(0.0, 1.0).pow(gamma)
  }

  class Table(
    private val entries: IccSamples,
  ) : IccCurve {
    override fun evaluate(x: Double): Double {
      val position = x.coerceIn(0.0, 1.0) * (entries.size - 1)
      val low = position.toInt().coerceAtMost(entries.size - 2)
      val weight = position - low
      return entries[low] + (entries[low + 1] - entries[low]) * weight
    }
  }

  /**
   * A `para` curve of [type] zero to four, holding the parameters g, a, b, c, d, e and f that type uses in order.
   */
  class Parametric(
    private val type: Int,
    private val params: DoubleArray,
  ) : IccCurve {
    override fun evaluate(x: Double): Double {
      val value = x.coerceIn(0.0, 1.0)
      val g = params[0]
      val y =
        when (type) {
          0 -> {
            value.pow(g)
          }
          1 -> {
            if (value >= -params[2] / params[1]) power(params[1] * value + params[2], g) else 0.0
          }
          2 -> {
            if (value >= -params[2] / params[1]) power(params[1] * value + params[2], g) + params[3] else params[3]
          }
          3 -> {
            if (value >= params[4]) power(params[1] * value + params[2], g) else params[3] * value
          }
          else -> {
            if (value >= params[4]) {
              power(params[1] * value + params[2], g) + params[5]
            } else {
              params[3] * value + params[6]
            }
          }
        }
      return y.coerceIn(0.0, 1.0)
    }

    private fun power(
      base: Double,
      exponent: Double,
    ): Double = base.coerceAtLeast(0.0).pow(exponent)
  }
}

/**
 * A matrix and curves profile whose [colorants] hold the D50 XYZ of red, green and blue in turn, X, Y and Z within
 * each, with a white of one.
 */
internal class MatrixTrcProfile(
  private val colorants: DoubleArray,
  private val curves: List<IccCurve>,
) : IccProfile {
  private val columns = colorants.copyOf()
  private val levels = curves.map { curve -> DoubleArray(CODES) { curve.evaluate(it / MAX_CODE) } }

  override fun toPcs(
    red: Double,
    green: Double,
    blue: Double,
  ): DoubleArray =
    DoubleArray(3).also { mix(curves[0].evaluate(red), curves[1].evaluate(green), curves[2].evaluate(blue), it) }

  override fun codesToPcs(
    rgb: Int,
    pcs: DoubleArray,
  ) = mix(
    levels[0][(rgb shr RED_SHIFT) and CODE_MASK],
    levels[1][(rgb shr GREEN_SHIFT) and CODE_MASK],
    levels[2][rgb and CODE_MASK],
    pcs,
  )

  override fun sampleReader(tabulate: Boolean): SampleReader {
    val (red, green, blue) = sampleCurves(curves, tabulate)
    return SampleReader { r, g, b, pcs -> mix(red.level(r), green.level(g), blue.level(b), pcs) }
  }

  private fun mix(
    red: Double,
    green: Double,
    blue: Double,
    pcs: DoubleArray,
  ) {
    for (row in 0 until 3) {
      var sum = 0.0
      sum += columns[row] * red
      sum += columns[3 + row] * green
      sum += columns[6 + row] * blue
      pcs[row] = sum
    }
  }

  override val isSrgb: Boolean =
    colorants.indices.all { abs(colorants[it] * S15_FIXED_16_ONE - SRGB_COLORANTS[it]) <= 1 } &&
      curves.all { it.matchesSrgb() }
}

internal class GreyProfile(
  private val curve: IccCurve,
) : IccProfile {
  private val levels = DoubleArray(CODES) { curve.evaluate(it / MAX_CODE) }

  override fun toPcs(
    red: Double,
    green: Double,
    blue: Double,
  ): DoubleArray {
    val level = curve.evaluate(green)
    return DoubleArray(3) { PCS_WHITE[it] * level }
  }

  override fun codesToPcs(
    rgb: Int,
    pcs: DoubleArray,
  ) {
    val level = levels[(rgb shr GREEN_SHIFT) and CODE_MASK]
    for (axis in 0 until 3) pcs[axis] = PCS_WHITE[axis] * level
  }

  override fun sampleReader(tabulate: Boolean): SampleReader {
    val grey = sampleCurves(listOf(curve), tabulate).single()
    return SampleReader { _, green, _, pcs ->
      val level = grey.level(green)
      for (axis in 0 until 3) pcs[axis] = PCS_WHITE[axis] * level
    }
  }

  override val isSrgb: Boolean = false
}

/**
 * A lookup table profile, run in the order an `mAB` tag lays it out: A curves, the color lookup table, M curves, the
 * matrix and then B curves. A `lut8` or `lut16` tag fills the A curves with its input tables and the B curves with its
 * output tables.
 */
internal class LutProfile(
  private val aCurves: List<IccCurve>?,
  private val clut: Clut?,
  private val mCurves: List<IccCurve>?,
  private val matrix: DoubleArray?,
  private val bCurves: List<IccCurve>?,
  private val pcs: PcsEncoding,
) : IccProfile {
  override fun toPcs(
    red: Double,
    green: Double,
    blue: Double,
  ): DoubleArray {
    var values = doubleArrayOf(red, green, blue)
    aCurves?.let { values = it.applyTo(values) }
    clut?.let { values = it.lookup(values) }
    mCurves?.let { values = it.applyTo(values) }
    matrix?.let { m ->
      values =
        DoubleArray(3) { row ->
          (m[row * 3] * values[0] + m[row * 3 + 1] * values[1] + m[row * 3 + 2] * values[2] + m[9 + row])
            .coerceIn(0.0, 1.0)
        }
    }
    bCurves?.let { values = it.applyTo(values) }
    return pcs.decode(values)
  }

  override val isSrgb: Boolean = false

  private fun List<IccCurve>.applyTo(values: DoubleArray): DoubleArray =
    DoubleArray(3) { this[it].evaluate(values[it]) }
}

/**
 * A three input, three output color lookup table, interpolated trilinearly between its grid points.
 *
 * The first input varies slowest through [samples], as the ICC spec lays a table out.
 */
internal class Clut(
  private val grid: IntArray,
  private val samples: IccSamples,
) {
  fun lookup(input: DoubleArray): DoubleArray {
    val low = IntArray(3)
    val weight = DoubleArray(3)
    for (axis in 0 until 3) {
      val position = input[axis].coerceIn(0.0, 1.0) * (grid[axis] - 1)
      low[axis] = position.toInt().coerceAtMost(grid[axis] - 2)
      weight[axis] = position - low[axis]
    }
    val out = DoubleArray(3)
    for (corner in 0 until 8) {
      var share = 1.0
      var index = 0
      for (axis in 0 until 3) {
        val high = (corner shr (2 - axis)) and 1
        share *= if (high == 1) weight[axis] else 1.0 - weight[axis]
        index = index * grid[axis] + (low[axis] + high).coerceAtMost(grid[axis] - 1)
      }
      if (share == 0.0) continue
      for (channel in 0 until 3) out[channel] += share * samples[index * 3 + channel]
    }
    return out
  }
}

/**
 * A table's stored entries, each a big-endian code [width] bytes wide, read as a level in the range zero to one.
 *
 * The codes stay as the profile stores them, and [get] divides one by the largest code of its width.
 */
internal class IccSamples(
  private val codes: ByteArray,
  private val width: Int,
) {
  val size: Int = codes.size / width

  operator fun get(index: Int): Double =
    if (width == 2) {
      (((codes[index * 2].toInt() and CODE_MASK) shl 8) or (codes[index * 2 + 1].toInt() and CODE_MASK)) /
        SIXTEEN_BIT_MAX.toDouble()
    } else {
      (codes[index].toInt() and CODE_MASK) / MAX_CODE
    }
}

/**
 * How a lookup table's output, in the range zero to one, encodes a connection space color.
 */
internal enum class PcsEncoding {
  Xyz,
  Lab,
  LegacyLab,
  ;

  fun decode(values: DoubleArray): DoubleArray =
    when (this) {
      Xyz -> {
        DoubleArray(3) { values[it] * XYZ_ENCODING_SCALE }
      }
      Lab -> {
        labToXyz(values[0] * 100.0, values[1] * 255.0 - 128.0, values[2] * 255.0 - 128.0)
      }
      LegacyLab -> {
        labToXyz(
          values[0] * LEGACY_LAB_SCALE * 100.0,
          values[1] * LEGACY_LAB_SCALE * 255.0 - 128.0,
          values[2] * LEGACY_LAB_SCALE * 255.0 - 128.0,
        )
      }
    }

  private fun labToXyz(
    lightness: Double,
    a: Double,
    b: Double,
  ): DoubleArray {
    val fy = (lightness + 16.0) / 116.0
    val f = doubleArrayOf(fy + a / 500.0, fy, fy - b / 200.0)
    return DoubleArray(3) { axis ->
      val t = f[axis]
      PCS_WHITE[axis] * if (t > LAB_EPSILON) t * t * t else 3.0 * LAB_EPSILON * LAB_EPSILON * (t - 4.0 / 29.0)
    }
  }
}

/**
 * One curve read at sixteen-bit samples, from [table] when it holds all 65536 levels and by evaluating the curve when
 * it is null. Both give the same level for a sample.
 */
private class SampleCurve(
  private val curve: IccCurve,
  private val table: DoubleArray?,
) {
  fun level(sample: Int): Double = if (table != null) table[sample] else curve.evaluate(sample / MAX_SAMPLE)
}

private fun sampleCurves(
  curves: List<IccCurve>,
  tabulate: Boolean,
): List<SampleCurve> {
  val tables = HashMap<IccCurve, DoubleArray>()
  return curves.map { curve ->
    val table = tables.takeIf { tabulate }?.getOrPut(curve) { DoubleArray(SAMPLES) { curve.evaluate(it / MAX_SAMPLE) } }
    SampleCurve(curve, table)
  }
}

private fun IccCurve.matchesSrgb(): Boolean {
  val tolerance = 1.0 / SIXTEEN_BIT_MAX + 1e-9
  return (0..SRGB_CURVE_SAMPLES).all {
    val x = it.toDouble() / SRGB_CURVE_SAMPLES
    abs(evaluate(x) - IccCurve.Srgb.evaluate(x)) <= tolerance
  }
}

private fun codeLevel(
  rgb: Int,
  shift: Int,
): Double = ((rgb shr shift) and CODE_MASK) / MAX_CODE

/**
 * The connection space white, D50 as the ICC spec stores it in s15Fixed16.
 */
internal val PCS_WHITE = doubleArrayOf(63190 / 65536.0, 1.0, 54061 / 65536.0)

// sRGB's colorants adapted to D50, in s15Fixed16 steps, red, green and blue in turn and X, Y and Z within each. These
// are the figures the sRGB profile most pictures embed carries.
private val SRGB_COLORANTS = intArrayOf(28578, 14581, 912, 25241, 46981, 6362, 9376, 3972, 46799)

internal const val S15_FIXED_16_ONE = 65536.0
private const val CODES = 256
private const val SAMPLES = 65536
private const val MAX_SAMPLE = 65535.0
private const val CODE_MASK = 0xFF
private const val MAX_CODE = 255.0
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val XYZ_ENCODING_SCALE = 65535.0 / 32768.0
private const val LEGACY_LAB_SCALE = 65535.0 / 65280.0
private const val LAB_EPSILON = 6.0 / 29.0
private const val SRGB_CURVE_SAMPLES = 1024
