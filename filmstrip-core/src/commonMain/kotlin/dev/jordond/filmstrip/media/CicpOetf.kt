package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The opto-electronic transfer function an ITU-T H.273 transfer characteristics code point names, taking linear light
 * to a signal in the range zero to one and back.
 *
 * A [YcbcrReading.ConstantLuminance] reading runs its luma through it. Light of one is the transfer's peak, except for
 * SMPTE ST 428, whose light reaches 52.37 / 48 at full signal. Each curve uses the constants H.273 Table 3 gives it,
 * so the BT.709, BT.601 and BT.2020 curves are one curve with alpha and beta at full precision. PQ and HLG have no
 * curve here, since [ycbcrReading] refuses constant luminance on them.
 *
 * @property transfer The transfer characteristics code point whose curve this is. Code points sharing a curve share
 * one value here, 1 standing for 1, 6, 11, 12, 14 and 15, and an unspecified, reserved or unknown code point reads as
 * 1, the curve BT.2020 constant luminance is defined with.
 * @throws IllegalArgumentException when [transfer] is PQ, HLG or any other code point that does not stand for its
 * curve here.
 */
@InternalFilmstripApi
@ConsistentCopyVisibility
public data class CicpOetf internal constructor(
  public val transfer: Int,
) {
  init {
    require(transfer == BT709 || transfer in OWN_CURVES) { "transfer $transfer does not stand for a curve" }
  }

  /**
   * The signal [light] encodes to, with light below zero or past the light of full signal clamped onto that range
   * first.
   */
  public fun encode(light: Double): Double {
    val l = light.coerceIn(0.0, if (transfer == ST_428) ST_428_PEAK else 1.0)
    val signal =
      when (transfer) {
        GAMMA_22 -> l.pow(1 / GAMMA_22_EXPONENT)
        GAMMA_28 -> l.pow(1 / GAMMA_28_EXPONENT)
        SMPTE_240M -> if (l >= SMPTE_240M_BETA) poweredSegment(l, SMPTE_240M_ALPHA) else SMPTE_240M_SLOPE * l
        LINEAR -> l
        LOG_100 -> if (l >= 0.01) 1.0 + log10(l) / 2.0 else 0.0
        LOG_316 -> if (l >= sqrt(10.0) / 1000.0) 1.0 + log10(l) / 2.5 else 0.0
        SRGB -> if (l > SRGB_LINEAR_KNEE) (1 + SRGB_OFFSET) * l.pow(1 / SRGB_GAMMA) - SRGB_OFFSET else SRGB_SLOPE * l
        ST_428 -> (l / ST_428_PEAK).pow(1 / ST_428_EXPONENT)
        else -> if (l >= BT709_BETA) poweredSegment(l, BT709_ALPHA) else 4.5 * l
      }
    return signal.coerceIn(0.0, 1.0)
  }

  /**
   * The linear light [signal] decodes to, the inverse of [encode], with a signal outside zero to one clamped onto it
   * first.
   */
  public fun decode(signal: Double): Double {
    val v = signal.coerceIn(0.0, 1.0)
    val light =
      when (transfer) {
        GAMMA_22 -> {
          v.pow(GAMMA_22_EXPONENT)
        }
        GAMMA_28 -> {
          v.pow(GAMMA_28_EXPONENT)
        }
        SMPTE_240M -> {
          if (v >= SMPTE_240M_SLOPE * SMPTE_240M_BETA) poweredLight(v, SMPTE_240M_ALPHA) else v / SMPTE_240M_SLOPE
        }
        LINEAR -> {
          v
        }
        LOG_100 -> {
          if (v > 0.0) 10.0.pow((v - 1.0) * 2.0) else 0.0
        }
        LOG_316 -> {
          if (v > 0.0) 10.0.pow((v - 1.0) * 2.5) else 0.0
        }
        SRGB -> {
          if (v > SRGB_ENCODED_KNEE) ((v + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA) else v / SRGB_SLOPE
        }
        ST_428 -> {
          ST_428_PEAK * v.pow(ST_428_EXPONENT)
        }
        else -> {
          if (v >= 4.5 * BT709_BETA) poweredLight(v, BT709_ALPHA) else v / 4.5
        }
      }
    return light.coerceIn(0.0, if (transfer == ST_428) ST_428_PEAK else 1.0)
  }
}

/**
 * The [CicpOetf] for the transfer characteristics code point [transfer].
 *
 * @throws IllegalArgumentException for PQ or HLG, which have no curve here.
 */
internal fun cicpOetf(transfer: Int): CicpOetf {
  require(transfer != PQ && transfer != HLG) { "transfer $transfer has no constant luminance curve" }
  return CicpOetf(if (transfer in OWN_CURVES) transfer else BT709)
}

// The power segment H.273 shares between BT.709 and SMPTE 240M, with alpha the curve's own.
private fun poweredSegment(
  light: Double,
  alpha: Double,
): Double = alpha * light.pow(POWER_SEGMENT_EXPONENT) - (alpha - 1)

private fun poweredLight(
  signal: Double,
  alpha: Double,
): Double = ((signal + alpha - 1) / alpha).pow(1 / POWER_SEGMENT_EXPONENT)

internal const val GAMMA_22_EXPONENT = 2.2
internal const val GAMMA_28_EXPONENT = 2.8
internal const val SMPTE_240M_ALPHA = 1.1115
internal const val SMPTE_240M_BETA = 0.0228
internal const val SMPTE_240M_SLOPE = 4.0
internal const val POWER_SEGMENT_EXPONENT = 0.45
internal const val ST_428_PEAK = 52.37 / 48.0
internal const val ST_428_EXPONENT = 2.6

private const val BT709_ALPHA = 1.09929682680944
private const val BT709_BETA = 0.018053968510807
private const val BT709 = 1
private const val GAMMA_22 = 4
private const val GAMMA_28 = 5
private const val SMPTE_240M = 7
private const val LINEAR = 8
private const val LOG_100 = 9
private const val LOG_316 = 10
private const val SRGB = 13
private const val PQ = 16
private const val ST_428 = 17
private const val HLG = 18
private val OWN_CURVES = setOf(GAMMA_22, GAMMA_28, SMPTE_240M, LINEAR, LOG_100, LOG_316, SRGB, ST_428)
