package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.roundToInt

// The ten-bit video range codes a BT.2020 frame stores, and the conversion between them and an
// R'G'B' signal on the matrix HdrSignal.kt holds.

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
 * Encoding a signal into ten-bit codes clamps to it, and so does a shader that packs ten-bit codes of its own.
 */
@InternalFilmstripApi
public const val TEN_BIT_MAX_CODE: Int = 1023

/**
 * The largest sixteen-bit sample, which a float plane holds as one.
 *
 * A sixteen-bit sample over this is the fraction of full scale a float plane holds it as.
 */
@InternalFilmstripApi
public const val SIXTEEN_BIT_MAX: Int = (1 shl 16) - 1

/**
 * How many sixteen-bit steps one ten-bit code spans.
 *
 * A ten-bit code carried onto sixteen bits is the code times this, which is how swscale widens one, and a sixteen-bit
 * sample comes back to ten bits by dividing by it and rounding.
 */
@InternalFilmstripApi
public const val TEN_TO_SIXTEEN_BITS: Int = (SIXTEEN_BIT_MAX + 1) / (TEN_BIT_MAX_CODE + 1)

/**
 * Encodes one R'G'B' [signal] as the ten-bit video range luma, Cb and Cr codes a BT.2020 frame stores.
 *
 * Runs [BT2020_ENCODE_ROWS] in double, then scales into video range, rounding to the nearest code and clamping between
 * zero and [TEN_BIT_MAX_CODE].
 *
 * @param signal Red, green and blue in the range zero to one, as [signalFromNits] returns them.
 * @return Luma, Cb and Cr in that order.
 */
@InternalFilmstripApi
public fun tenBitCodesFromSignal(signal: FloatArray): IntArray {
  val red = signal[0].toDouble()
  val green = signal[1].toDouble()
  val blue = signal[2].toDouble()
  val luma = BT2020_ENCODE_ROWS[0].weigh(red, green, blue)
  val cb = BT2020_ENCODE_ROWS[1].weigh(red, green, blue)
  val cr = BT2020_ENCODE_ROWS[2].weigh(red, green, blue)

  return intArrayOf(
    tenBitCode(TEN_BIT_LUMA_FLOOR + TEN_BIT_LUMA_RANGE * luma),
    tenBitCode(TEN_BIT_CHROMA_MID + TEN_BIT_CHROMA_RANGE * cb),
    tenBitCode(TEN_BIT_CHROMA_MID + TEN_BIT_CHROMA_RANGE * cr),
  )
}

/**
 * Decodes the ten-bit video range luma, [cb] and [cr] codes a BT.2020 frame stores back to one R'G'B' signal.
 *
 * The inverse of [tenBitCodesFromSignal], run on [BT2020_DECODE_ROWS] in double and rounded once, so a caller reading a
 * written frame does not carry a second copy of the matrix. Clamped to the range zero to one, since a code outside
 * video range decodes past it.
 *
 * @return Red, green and blue in that order.
 */
@InternalFilmstripApi
public fun signalFromTenBitCodes(
  luma: Int,
  cb: Int,
  cr: Int,
): FloatArray {
  val y = (luma - TEN_BIT_LUMA_FLOOR).toDouble() / TEN_BIT_LUMA_RANGE
  val u = (cb - TEN_BIT_CHROMA_MID).toDouble() / TEN_BIT_CHROMA_RANGE
  val v = (cr - TEN_BIT_CHROMA_MID).toDouble() / TEN_BIT_CHROMA_RANGE

  return FloatArray(3) { channel ->
    BT2020_DECODE_ROWS[channel]
      .weigh(y, u, v)
      .coerceIn(0.0, 1.0)
      .toFloat()
  }
}

private fun ChannelTriple.weigh(
  first: Double,
  second: Double,
  third: Double,
): Double = this[0] * first + this[1] * second + this[2] * third

private fun tenBitCode(value: Double): Int = value.roundToInt().coerceIn(0, TEN_BIT_MAX_CODE)
