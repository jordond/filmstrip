package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The matrix coefficients code point among [readable] that a decoder reading it lands this reading's samples closest
 * with, and how far it lands them, or null when this is [YcbcrReading.Refused] or [readable] holds nothing that reads.
 *
 * A code point that reads the same as this one lands exactly, with no error. Otherwise each code point in [readable]
 * is read the way [ycbcrReading] reads it with unspecified primaries, a constant luminance one with this reading's
 * curve. A [YcbcrReading.ConstantLuminance] lands through the code point with its own Kr and Kb when [readable] holds
 * one, so BT.2020 constant luminance lands through 9. Any other reading never lands through a constant luminance code
 * point. Every other landing goes to the code point with the smallest [NearestMatrix.meanDeltaE], the lower code point
 * on a tie. The answer for each reading and set of code points is kept for the life of the process, so a repeat call
 * skips the measuring.
 *
 * @param readable The matrix coefficients code points a decoder reads.
 */
@InternalFilmstripApi
public fun YcbcrReading.nearestReadable(readable: Set<Int>): NearestMatrix? = nearest(readable, SAME_RANGES)

/**
 * The matrix coefficients code point among [readable] that a decoder reading samples at [decoderFullRange] lands this
 * reading's samples at [fullRange] closest with, and how far it lands them, or null when this is
 * [YcbcrReading.Refused] or [readable] holds nothing that reads.
 *
 * When the two ranges agree this is the [nearestReadable] that takes no range. Otherwise the samples are encoded at
 * [fullRange] and decoded at [decoderFullRange], so [NearestMatrix.meanDeltaE] and [NearestMatrix.worstError] hold the
 * shift of the range as well as the matrix, and no landing is [NearestMatrix.exact]. The code point is picked from
 * [readable] the same way, measuring the whole landing.
 *
 * @param readable The matrix coefficients code points a decoder reads.
 * @param fullRange Whether the picture's own samples are at full range.
 * @param decoderFullRange Whether the decoder reads the samples as full range.
 */
@InternalFilmstripApi
public fun YcbcrReading.nearestReadable(
  readable: Set<Int>,
  fullRange: Boolean,
  decoderFullRange: Boolean,
): NearestMatrix? =
  if (fullRange == decoderFullRange) {
    nearestReadable(readable)
  } else {
    nearest(readable, listOf(fullRange to decoderFullRange))
  }

@OptIn(ExperimentalAtomicApi::class)
private fun YcbcrReading.nearest(
  readable: Set<Int>,
  ranges: List<Pair<Boolean, Boolean>>,
): NearestMatrix? {
  if (this is YcbcrReading.Refused) return null
  val key = NearestKey(this, readable.toSet(), ranges)
  val known = NEAREST_MEMO.load()
  if (key in known) return known[key]
  val nearest = findNearest(key.readable, ranges)
  while (true) {
    val current = NEAREST_MEMO.load()
    if (key in current || NEAREST_MEMO.compareAndSet(current, current + (key to nearest))) return nearest
  }
}

/**
 * A matrix coefficients code point a decoder lands a picture with in place of the picture's own, and how far that
 * moves the picture's colors, as [nearestReadable] finds it.
 *
 * Both figures come from encoding every red, green and blue on a grid with the picture's own reading at full and at
 * limited range, without rounding, and decoding the samples through [matrix] at the same range. The [nearestReadable]
 * that takes ranges encodes at the picture's range and decodes at the decoder's instead.
 *
 * @property matrix The code point the decoder reads.
 * @property meanDeltaE The mean CIE 1976 color difference between each color and where it lands, over a grid of
 * every channel at 17 evenly spaced levels, with both colors read as sRGB.
 * @property worstError The furthest any red, green or blue channel lands from where it started, in 8-bit codes, over a
 * grid of every channel at 33 evenly spaced levels.
 * @property exact Whether [matrix] reads the same as the picture's own reading at the picture's own range, so the
 * landing moves nothing.
 */
@InternalFilmstripApi
public data class NearestMatrix(
  public val matrix: Int,
  public val meanDeltaE: Double,
  public val worstError: Double,
  public val exact: Boolean,
) {
  /**
   * Whether the landing is [exact] or its [meanDeltaE] is within [NEAREST_MATRIX_DELTA_E_BOUND], so a backend reports
   * it as degraded rather than refusing the picture as unsupported.
   */
  public val withinBound: Boolean
    get() = exact || meanDeltaE <= NEAREST_MATRIX_DELTA_E_BOUND

  /**
   * How far the landing moves the picture's colors, worded for a degraded or unsupported cause to end on, like "a
   * mean color difference of 6.8 dE76, with one channel up to 41.2 8-bit codes out".
   *
   * Every backend ends its cause with these same words. The lead-in before them is the backend's own and names the
   * decoder that reads the picture through [matrix].
   */
  public val figures: String
    get() =
      "a mean color difference of ${oneDecimal(meanDeltaE)} dE76, with one channel up to " +
        "${oneDecimal(worstError)} 8-bit codes out"
}

/**
 * The largest [NearestMatrix.meanDeltaE] a landing through a matrix other than the picture's own may leave and still
 * be drawn.
 *
 * A backend whose decoder reads the picture's samples through [NearestMatrix.matrix] reports the result as degraded
 * when the mean color difference is within this bound, and refuses the picture as unsupported past it.
 */
@InternalFilmstripApi
public const val NEAREST_MATRIX_DELTA_E_BOUND: Double = 12.0

/**
 * The mean color difference and the worst channel error of one landing, in the units [NearestMatrix.meanDeltaE] and
 * [NearestMatrix.worstError] use.
 */
internal class LandingError(
  val meanDeltaE: Double,
  val worstError: Double,
)

/**
 * How far samples encoded under [source] land from their colors when read through [landing], as a [NearestMatrix]
 * reports it.
 *
 * @param ranges Each pair of the range the samples are encoded at and the range they are decoded at, true for full,
 * with the figures taken over every pair together.
 */
internal fun landingError(
  source: YcbcrReading,
  landing: YcbcrReading,
  ranges: List<Pair<Boolean, Boolean>> = SAME_RANGES,
): LandingError {
  var worst = 0.0
  var total = 0.0
  var count = 0
  val decoded = DoubleArray(3)
  for ((fullRange, decoderFullRange) in ranges) {
    val range = sampleRange(MEASURE_BIT_DEPTH, fullRange)
    val decoder = YcbcrEvaluator(landing, sampleRange(MEASURE_BIT_DEPTH, decoderFullRange))
    cube(WORST_ERROR_STEPS) { _, rgb ->
      val codes = encode(source, rgb, range)
      decoder.rgb(codes[0], codes[1], codes[2], decoded)
      for (channel in 0 until 3) worst = maxOf(worst, abs(decoded[channel] - rgb[channel]) * MAX_8_BIT)
    }
    cube(DELTA_E_STEPS) { index, rgb ->
      val codes = encode(source, rgb, range)
      decoder.rgb(codes[0], codes[1], codes[2], decoded)
      total += deltaE76(DELTA_E_GRID[index], srgbLab(decoded))
      count++
    }
  }
  return LandingError(total / count, worst)
}

private class NearestKey(
  val reading: YcbcrReading,
  val readable: Set<Int>,
  val ranges: List<Pair<Boolean, Boolean>>,
) {
  override fun equals(other: Any?): Boolean =
    other is NearestKey && other.reading == reading && other.readable == readable && other.ranges == ranges

  override fun hashCode(): Int = (reading.hashCode() * 31 + readable.hashCode()) * 31 + ranges.hashCode()
}

private fun YcbcrReading.findNearest(
  readable: Set<Int>,
  ranges: List<Pair<Boolean, Boolean>>,
): NearestMatrix? {
  val sameRange = ranges.all { (fullRange, decoderFullRange) -> fullRange == decoderFullRange }
  if (sameRange) equivalentCode()?.takeIf { it in readable }?.let { return NearestMatrix(it, 0.0, 0.0, exact = true) }
  val transfer = (this as? YcbcrReading.ConstantLuminance)?.oetf?.transfer ?: UNSPECIFIED
  val landings =
    readable
      .sorted()
      .map { it to ycbcrReading(it, UNSPECIFIED, transfer) }
      .filter { (_, landing) -> landing !is YcbcrReading.Refused }
      .filter { (_, landing) -> this is YcbcrReading.ConstantLuminance || landing !is YcbcrReading.ConstantLuminance }
  if (sameRange) {
    landings.firstOrNull { (_, landing) -> landing == this }?.let { return NearestMatrix(it.first, 0.0, 0.0, true) }
  }
  val ownWeights =
    (this as? YcbcrReading.ConstantLuminance)?.let { YcbcrReading.KrKb(it.kr, it.kb).equivalentCode() }
  val candidates = landings.filter { (code, _) -> code == ownWeights }.ifEmpty { landings }
  val measured = candidates.map { (code, landing) -> code to landingError(this, landing, ranges) }
  val (code, error) = measured.minByOrNull { it.second.meanDeltaE } ?: return null
  return NearestMatrix(code, error.meanDeltaE, error.worstError, exact = false)
}

// Every red, green and blue at steps + 1 evenly spaced levels, handed over with its place in that order.
private inline fun cube(
  steps: Int,
  each: (index: Int, rgb: DoubleArray) -> Unit,
) {
  val rgb = DoubleArray(3)
  var index = 0
  for (r in 0..steps) {
    for (g in 0..steps) {
      for (b in 0..steps) {
        rgb[0] = r.toDouble() / steps
        rgb[1] = g.toDouble() / steps
        rgb[2] = b.toDouble() / steps
        each(index++, rgb)
      }
    }
  }
}

// CIE L*a*b* of a color whose channels are sRGB codes in zero to one, against the sRGB white.
private fun srgbLab(rgb: DoubleArray): DoubleArray {
  val curve = cicpOetf(SRGB_TRANSFER)
  val linear = DoubleArray(3) { curve.decode(rgb[it]) }
  val xyz = DoubleArray(3) { row -> (0 until 3).sumOf { SRGB_TO_XYZ[row * 3 + it] * linear[it] } / SRGB_WHITE[row] }
  val (fx, fy, fz) = xyz.map { if (it > LAB_EPSILON) cbrt(it) else it / LAB_SLOPE + LAB_OFFSET }
  return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

private fun deltaE76(
  a: DoubleArray,
  b: DoubleArray,
): Double = sqrt((0 until 3).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

private fun oneDecimal(value: Double): String {
  val tenths = (value * 10).roundToLong()
  return "${tenths / 10}.${tenths % 10}"
}

@OptIn(ExperimentalAtomicApi::class)
private val NEAREST_MEMO = AtomicReference<Map<NearestKey, NearestMatrix?>>(emptyMap())

private val SRGB_TO_XYZ: DoubleArray = checkNotNull(cicpChromaticities(SRGB_PRIMARIES)?.let(::rgbToXyz))
private val SRGB_WHITE: DoubleArray = DoubleArray(3) { row -> (0 until 3).sumOf { SRGB_TO_XYZ[row * 3 + it] } }

private val DELTA_E_GRID: List<DoubleArray> by lazy {
  buildList { cube(DELTA_E_STEPS) { _, rgb -> add(srgbLab(rgb)) } }
}

private val SAME_RANGES = listOf(true to true, false to false)

private const val UNSPECIFIED = 2
private const val MEASURE_BIT_DEPTH = 8
private const val WORST_ERROR_STEPS = 32
private const val DELTA_E_STEPS = 16
private const val MAX_8_BIT = 255.0
private const val LAB_EPSILON = 216.0 / 24389.0
private const val LAB_SLOPE = 3 * (6.0 / 29.0) * (6.0 / 29.0)
private const val LAB_OFFSET = 4.0 / 29.0
