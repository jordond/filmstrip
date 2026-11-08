package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The [YcbcrReading] the matrix coefficients code point [matrix] stands for, beside color primaries [primaries] and
 * transfer characteristics [transfer].
 *
 * - 0 reads as [YcbcrReading.Identity] and 8 as [YcbcrReading.YCgCo].
 * - 1, 4, 5, 6, 7 and 9 read as [YcbcrReading.KrKb] with their Kr and Kb from H.273 Table 4, and 2 for unspecified
 * reads as [UNSPECIFIED_MATRIX_DECODES_AS] does.
 * - 10 reads as [YcbcrReading.ConstantLuminance] with BT.2020's Kr and Kb and the curve [transfer] names.
 * - 12 and 13 take Kr and Kb from the luminance row of the RGB to XYZ matrix of [primaries], 12 with the non-constant
 * luminance equations and 13 with the constant luminance ones. Where a named matrix's Table 4 Kr and Kb sit within
 * 0.0001 of that, the reading carries the named matrix's values, so 12 on BT.709 primaries reads as 1 and 13 on BT.2020
 * primaries as 10. On primaries core has no chromaticities for they read as [YcbcrReading.Refused].
 * - 10, and 13 on primaries core has chromaticities for, read as [YcbcrReading.Refused] beside the PQ or HLG transfer,
 * 16 or 18, with a reason that names it.
 * - 11 for YDzDx, 14 for ICtCp, 3 and every code point past 14 or below 0 read as [YcbcrReading.Refused], each with
 * a reason that names it.
 */
@InternalFilmstripApi
public fun ycbcrReading(
  matrix: Int,
  primaries: Int,
  transfer: Int,
): YcbcrReading =
  when (matrix) {
    IDENTITY_MATRIX -> {
      YcbcrReading.Identity
    }
    UNSPECIFIED -> {
      NAMED_READINGS.getValue(UNSPECIFIED_MATRIX_DECODES_AS)
    }
    in NAMED_READINGS -> {
      NAMED_READINGS.getValue(matrix)
    }
    YCGCO_MATRIX -> {
      YcbcrReading.YCgCo
    }
    BT2020_CONSTANT_MATRIX -> {
      constantLuminance(BT2020_READING, transfer)
    }
    CHROMATICITY_DERIVED, CHROMATICITY_DERIVED_CONSTANT -> {
      val derived = derivedKrKb(primaries)?.let { named(it) ?: it }
      when {
        derived == null -> YcbcrReading.Refused(UNDERIVED_MATRIX_REASON)
        matrix == CHROMATICITY_DERIVED -> derived
        else -> constantLuminance(derived, transfer)
      }
    }
    YDZDX_MATRIX -> {
      YcbcrReading.Refused(YDZDX_MATRIX_REASON)
    }
    ICTCP_MATRIX -> {
      YcbcrReading.Refused(ICTCP_MATRIX_REASON)
    }
    else -> {
      YcbcrReading.Refused(unreadMatrixReason(matrix))
    }
  }

/**
 * The named matrix coefficients code point that reads the same as this, or null when none does.
 *
 * [YcbcrReading.Identity] is 0 and [YcbcrReading.YCgCo] is 8. A [YcbcrReading.KrKb] is the first of 1, 6, 4, 7 and 9
 * whose H.273 Table 4 Kr and Kb sit within 0.0001 of its own, 6 standing for BT.601 since 5 carries the same values.
 * A [YcbcrReading.ConstantLuminance] with BT.2020's Kr and Kb is 10, which reads its transfer's curve too. Any other
 * reading, and [YcbcrReading.Refused], has none.
 */
@InternalFilmstripApi
public fun YcbcrReading.equivalentCode(): Int? =
  when (this) {
    YcbcrReading.Identity -> IDENTITY_MATRIX
    YcbcrReading.YCgCo -> YCGCO_MATRIX
    is YcbcrReading.KrKb -> NAMED_ORDER.firstOrNull { NAMED_READINGS.getValue(it).agrees(kr, kb) }
    is YcbcrReading.ConstantLuminance -> BT2020_CONSTANT_MATRIX.takeIf { BT2020_READING.agrees(kr, kb) }
    is YcbcrReading.Refused -> null
  }

/**
 * The red, green and blue, each in the range zero to one, that the [bitDepth]-bit samples [y], [cb] and [cr] decode to
 * under [reading].
 *
 * Limited range samples take luma over 16 to 235 and chroma over 16 to 240, scaled up for a deeper [bitDepth], and
 * full range ones take luma over 0 to 2^[bitDepth] - 1 and chroma around 2^([bitDepth] - 1) over the same span. The
 * chroma of [YcbcrReading.Identity] and [YcbcrReading.YCgCo] is scaled as luma is, the way H.273 builds them from
 * red, green and blue codes. A channel that decodes outside zero to one is clamped onto it.
 *
 * A caller decoding many samples at one depth and range prepares a [YcbcrEvaluator] once with [evaluator] instead.
 *
 * @throws IllegalArgumentException for a [YcbcrReading.Refused] reading, or a [bitDepth] outside 8 to 16.
 */
@InternalFilmstripApi
public fun ycbcrToRgb(
  reading: YcbcrReading,
  y: Int,
  cb: Int,
  cr: Int,
  bitDepth: Int,
  fullRange: Boolean,
): DoubleArray = reading.evaluator(bitDepth, fullRange).rgb(y, cb, cr)

/**
 * A [YcbcrEvaluator] decoding [bitDepth]-bit samples at full or limited range under this reading, as [ycbcrToRgb] does,
 * with the matrix or the constant luminance divisors worked out once.
 *
 * @throws IllegalArgumentException for a [YcbcrReading.Refused] reading, or a [bitDepth] outside 8 to 16.
 */
@InternalFilmstripApi
public fun YcbcrReading.evaluator(
  bitDepth: Int,
  fullRange: Boolean,
): YcbcrEvaluator = YcbcrEvaluator(this, sampleRange(bitDepth, fullRange))

/**
 * The [bitDepth]-bit luma, blue difference and red difference samples at full or limited range this reading codes the
 * sixteen-bit [red], [green] and [blue] codes as.
 *
 * They come from the forward equations of H.273 section 8.3, the ones [ycbcrToRgb] undoes, each rounded to the nearest
 * sample and held to the samples [bitDepth] bits store.
 *
 * @throws IllegalArgumentException for a [YcbcrReading.Refused] reading, or a [bitDepth] outside 8 to 16.
 */
@InternalFilmstripApi
public fun YcbcrReading.ycbcrCodes(
  red: Int,
  green: Int,
  blue: Int,
  bitDepth: Int,
  fullRange: Boolean,
): IntArray {
  val rgb = doubleArrayOf(red / WIDE_CODE, green / WIDE_CODE, blue / WIDE_CODE)
  val top = (1 shl bitDepth) - 1
  return encode(this, rgb, sampleRange(bitDepth, fullRange)).map { it.roundToInt().coerceIn(0, top) }.toIntArray()
}

/**
 * The matrix and offsets taking [bitDepth]-bit samples straight to red, green and blue in the range zero to one under
 * this reading, or null for [YcbcrReading.ConstantLuminance] and [YcbcrReading.Refused], whose decode is not linear.
 *
 * Samples scale the way [ycbcrToRgb] scales them, and for every reading this gives one, [ycbcrToRgb] is this matrix
 * with each channel clamped onto zero to one.
 *
 * @throws IllegalArgumentException for a [bitDepth] outside 8 to 16.
 */
@InternalFilmstripApi
public fun YcbcrReading.rgbMatrix(
  bitDepth: Int,
  fullRange: Boolean,
): YcbcrMatrix? = linearMatrix(this, sampleRange(bitDepth, fullRange))

/**
 * The Kr and Kb H.273 derives from [primaries], the luminance row of their RGB to XYZ matrix, or null when core has no
 * chromaticities for them.
 */
internal fun derivedKrKb(primaries: Int): YcbcrReading.KrKb? {
  val toXyz = cicpChromaticities(primaries)?.let { rgbToXyz(it) } ?: return null
  return YcbcrReading.KrKb(toXyz[3], toXyz[5])
}

private fun YcbcrReading.KrKb.agrees(
  kr: Double,
  kb: Double,
): Boolean = abs(this.kr - kr) < KR_KB_TOLERANCE && abs(this.kb - kb) < KR_KB_TOLERANCE

private fun named(derived: YcbcrReading.KrKb): YcbcrReading.KrKb? =
  NAMED_ORDER.map { NAMED_READINGS.getValue(it) }.firstOrNull { it.agrees(derived.kr, derived.kb) }

private fun constantLuminance(
  weights: YcbcrReading.KrKb,
  transfer: Int,
): YcbcrReading =
  if (transfer == PQ_TRANSFER || transfer == HLG_TRANSFER) {
    YcbcrReading.Refused(HDR_CONSTANT_LUMINANCE_REASON)
  } else {
    YcbcrReading.ConstantLuminance(weights.kr, weights.kb, cicpOetf(transfer))
  }

private fun unreadMatrixReason(matrix: Int): String =
  "The picture names matrix coefficients $matrix, which ITU-T H.273 reserves or filmstrip does not read."

internal const val YDZDX_MATRIX_REASON: String =
  "The picture names the YDzDx matrix, matrix coefficients 11, which filmstrip does not read."
internal const val ICTCP_MATRIX_REASON: String =
  "The picture names the ICtCp matrix, matrix coefficients 14, which filmstrip does not read."
internal const val UNDERIVED_MATRIX_REASON: String =
  "The picture names a matrix derived from its color primaries, on primaries filmstrip has no chromaticities for."
internal const val HDR_CONSTANT_LUMINANCE_REASON: String =
  "The picture names a constant luminance matrix beside the PQ or HLG transfer, which filmstrip does not read."

private const val UNSPECIFIED = 2
private const val WIDE_CODE = 65535.0
private const val YCGCO_MATRIX = 8
private const val BT2020_CONSTANT_MATRIX = 10
private const val YDZDX_MATRIX = 11
private const val CHROMATICITY_DERIVED = 12
private const val CHROMATICITY_DERIVED_CONSTANT = 13
private const val ICTCP_MATRIX = 14
private const val PQ_TRANSFER = 16
private const val HLG_TRANSFER = 18
private const val BT2020_KR = 0.2627
private const val BT2020_KB = 0.0593
private const val KR_KB_TOLERANCE = 0.0001

private val BT601_READING = YcbcrReading.KrKb(0.299, 0.114)
private val BT2020_READING = YcbcrReading.KrKb(BT2020_KR, BT2020_KB)

// Kr and Kb of each matrix H.273 Table 4 gives for the non-constant luminance equations.
private val NAMED_READINGS: Map<Int, YcbcrReading.KrKb> =
  mapOf(
    1 to YcbcrReading.KrKb(0.2126, 0.0722),
    4 to YcbcrReading.KrKb(0.30, 0.11),
    5 to BT601_READING,
    6 to BT601_READING,
    7 to YcbcrReading.KrKb(0.212, 0.087),
    9 to BT2020_READING,
  )

private val NAMED_ORDER = listOf(1, 6, 4, 7, 9)
