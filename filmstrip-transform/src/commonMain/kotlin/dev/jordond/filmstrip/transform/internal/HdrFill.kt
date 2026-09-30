package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.BT2020_CB_SCALE
import dev.jordond.filmstrip.media.BT2020_CR_SCALE
import dev.jordond.filmstrip.media.BT2020_LUMA_B
import dev.jordond.filmstrip.media.BT2020_LUMA_G
import dev.jordond.filmstrip.media.BT2020_LUMA_R
import dev.jordond.filmstrip.media.signalFromNits
import kotlin.math.roundToInt

/**
 * The ten-bit code black sits at in video range.
 *
 * Luma runs from here up by [TEN_BIT_LUMA_RANGE], the video range a ten-bit BT.2100 signal is stored in.
 */
@InternalFilmstripApi
public const val TEN_BIT_LUMA_FLOOR: Int = 64

/**
 * How many ten-bit codes luma spans from black to white in video range.
 *
 * White lands on 940, the floor plus this.
 */
@InternalFilmstripApi
public const val TEN_BIT_LUMA_RANGE: Int = 876

/**
 * The ten-bit code a chroma channel is centred on in video range.
 *
 * A neutral colour carries this on both Cb and Cr.
 */
@InternalFilmstripApi
public const val TEN_BIT_CHROMA_MID: Int = 512

/**
 * How many ten-bit codes a chroma channel spans in video range.
 *
 * Cb and Cr run plus or minus a half, so each extreme sits half this far from [TEN_BIT_CHROMA_MID].
 */
@InternalFilmstripApi
public const val TEN_BIT_CHROMA_RANGE: Int = 896

/**
 * The highest code ten bits hold.
 *
 * [tenBitCodesFromSignal] clamps to it, and so does a shader that packs ten-bit codes of its own.
 */
@InternalFilmstripApi
public const val TEN_BIT_MAX_CODE: Int = 1023

/**
 * Encodes one R'G'B' [signal] as the ten-bit video range luma, Cb and Cr codes a BT.2020 frame stores.
 *
 * Runs the BT.2020 non-constant luminance matrix built on [BT2020_LUMA_R], [BT2020_CB_SCALE] and their siblings, then
 * scales into video range, rounding to the nearest code and clamping between zero and [TEN_BIT_MAX_CODE].
 *
 * @param signal Red, green and blue in the range zero to one, as [signalFromNits] returns them.
 * @return Luma, Cb and Cr in that order.
 */
@InternalFilmstripApi
public fun tenBitCodesFromSignal(signal: FloatArray): IntArray {
  val luma = BT2020_LUMA_R * signal[0] + BT2020_LUMA_G * signal[1] + BT2020_LUMA_B * signal[2]
  val cb = (signal[2] - luma) / BT2020_CB_SCALE
  val cr = (signal[0] - luma) / BT2020_CR_SCALE

  return intArrayOf(
    tenBitCode(TEN_BIT_LUMA_FLOOR + TEN_BIT_LUMA_RANGE * luma),
    tenBitCode(TEN_BIT_CHROMA_MID + TEN_BIT_CHROMA_RANGE * cb),
    tenBitCode(TEN_BIT_CHROMA_MID + TEN_BIT_CHROMA_RANGE * cr),
  )
}

/**
 * Decodes the ten-bit video range luma, [cb] and [cr] codes a BT.2020 frame stores back to one R'G'B' signal.
 *
 * The inverse of [tenBitCodesFromSignal], run on the same matrix so a caller reading a written frame does not carry a
 * second copy of it. Clamped to the range zero to one, since a code outside video range decodes past it.
 *
 * @return Red, green and blue in that order.
 */
@InternalFilmstripApi
public fun signalFromTenBitCodes(
  luma: Int,
  cb: Int,
  cr: Int,
): FloatArray {
  val y = (luma - TEN_BIT_LUMA_FLOOR).toFloat() / TEN_BIT_LUMA_RANGE
  val blueDiff = (cb - TEN_BIT_CHROMA_MID).toFloat() / TEN_BIT_CHROMA_RANGE
  val redDiff = (cr - TEN_BIT_CHROMA_MID).toFloat() / TEN_BIT_CHROMA_RANGE
  val red = y + BT2020_CR_SCALE * redDiff
  val blue = y + BT2020_CB_SCALE * blueDiff
  val green = (y - BT2020_LUMA_R * red - BT2020_LUMA_B * blue) / BT2020_LUMA_G

  return floatArrayOf(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f))
}

private fun tenBitCode(value: Float): Int = value.roundToInt().coerceIn(0, TEN_BIT_MAX_CODE)
