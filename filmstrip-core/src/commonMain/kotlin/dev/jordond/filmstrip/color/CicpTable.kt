package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.color.icc.IccCurve
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.pow

// The ITU-T H.273 code points a still picture's color can be described with, and the chromaticities and tone curves
// each one stands for.

/**
 * Whether [Cicp]'s primaries and transfer are ones this evaluation reads.
 *
 * Unspecified, reserved and unsupported primaries or transfers are not. The matrix and range are not checked here,
 * since how the samples read, or why they are refused, is [ycbcrReading]'s to say.
 */
internal val Cicp.isSupported: Boolean
  get() = primaries in CICP_PRIMARIES && cicpCurve(transfer) != null

/**
 * Whether [Cicp]'s primaries or transfer is 2, which H.273 reserves for unspecified, so the code points say nothing
 * about the picture's color.
 */
internal val Cicp.isUnspecified: Boolean
  get() = primaries == UNSPECIFIED || transfer == UNSPECIFIED

/**
 * The HDR transfer [Cicp]'s transfer names, PQ for 16 and HLG for 18, or null for one whose light tops out at
 * reference white.
 */
internal val Cicp.hdrTransfer: HdrTransfer?
  get() =
    when (transfer) {
      PQ_TRANSFER -> HdrTransfer.Pq
      HLG_TRANSFER -> HdrTransfer.Hlg
      else -> null
    }

/**
 * The red, green, blue and white chromaticities, x then y, H.273 gives [primaries], or null when it is not one this
 * evaluation reads.
 */
internal fun cicpChromaticities(primaries: Int): DoubleArray? = CICP_PRIMARIES[primaries]

/**
 * The tone curve taking a stored level to linear light that H.273 gives [transfer], or null when it is not one this
 * evaluation reads.
 *
 * BT.709, BT.601, BT.2020 and IEC 61966-2-4 read through BT.1886's gamma of 2.4, the curve a display shows them with.
 * SMPTE ST 428's curve reaches past one at full level, and clips there with every other curve's range. PQ reads to
 * light relative to reference white and HLG to scene light, as [IccCurve.Pq] and [IccCurve.HlgScene] describe.
 */
internal fun cicpCurve(transfer: Int): IccCurve? =
  when (transfer) {
    1, 6, 11, 14, 15 -> BT1886_CURVE
    4 -> GAMMA_22_CURVE
    5 -> GAMMA_28_CURVE
    7 -> SMPTE_240M_CURVE
    8 -> IccCurve.Identity
    SRGB_TRANSFER -> SRGB_CURVE
    PQ_TRANSFER -> IccCurve.Pq
    17 -> ST_428_CURVE
    HLG_TRANSFER -> IccCurve.HlgScene
    else -> null
  }

/**
 * The sRGB curve, read through the same [srgbToLinear] an untagged picture lands with.
 */
internal val SRGB_CURVE: IccCurve = IccCurve.Srgb

internal const val SRGB_PRIMARIES = 1
internal const val SRGB_TRANSFER = 13

private val BT1886_CURVE = IccCurve.Gamma(2.4)
private val GAMMA_22_CURVE = IccCurve.Gamma(GAMMA_22_EXPONENT)
private val GAMMA_28_CURVE = IccCurve.Gamma(GAMMA_28_EXPONENT)
private val SMPTE_240M_CURVE =
  IccCurve.Parametric(
    3,
    doubleArrayOf(
      1 / POWER_SEGMENT_EXPONENT,
      1 / SMPTE_240M_ALPHA,
      (SMPTE_240M_ALPHA - 1) / SMPTE_240M_ALPHA,
      1 / SMPTE_240M_SLOPE,
      SMPTE_240M_SLOPE * SMPTE_240M_BETA,
    ),
  )
private val ST_428_CURVE =
  IccCurve.Parametric(1, doubleArrayOf(ST_428_EXPONENT, ST_428_PEAK.pow(1 / ST_428_EXPONENT), 0.0))

private const val UNSPECIFIED = 2
private const val PQ_TRANSFER = 16
private const val HLG_TRANSFER = 18

private val D65 = doubleArrayOf(0.3127, 0.3290)
private val ILLUMINANT_C = doubleArrayOf(0.310, 0.316)
private val SMPTE_170M = doubleArrayOf(0.630, 0.340, 0.310, 0.595, 0.155, 0.070) + D65
private val P3 = doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)

private val CICP_PRIMARIES: Map<Int, DoubleArray> =
  mapOf(
    SRGB_PRIMARIES to doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060) + D65,
    4 to doubleArrayOf(0.67, 0.33, 0.21, 0.71, 0.14, 0.08) + ILLUMINANT_C,
    5 to doubleArrayOf(0.64, 0.33, 0.29, 0.60, 0.15, 0.06) + D65,
    6 to SMPTE_170M,
    7 to SMPTE_170M,
    8 to doubleArrayOf(0.681, 0.319, 0.243, 0.692, 0.145, 0.049) + ILLUMINANT_C,
    9 to doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046) + D65,
    10 to doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1 / 3.0, 1 / 3.0),
    11 to P3 + doubleArrayOf(0.314, 0.351),
    12 to P3 + D65,
    22 to doubleArrayOf(0.630, 0.340, 0.295, 0.605, 0.155, 0.077) + D65,
  )
