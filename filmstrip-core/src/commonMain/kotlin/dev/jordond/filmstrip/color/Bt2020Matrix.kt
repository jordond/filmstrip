package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

// The BT.2020 luminance weights and the non-constant luminance matrix built on them, written once for the HLG transfer
// and the ten-bit codes to read.

/**
 * BT.2020's red luminance weight.
 *
 * The three weights sum to one and are what turn linear BT.2020 light into luminance, which is both
 * what HLG's opto-optical transfer is anchored on and what a Y'CbCr matrix reads.
 */
@InternalFilmstripApi
public const val BT2020_LUMA_R: Float = 0.2627f

/**
 * BT.2020's green luminance weight.
 */
@InternalFilmstripApi
public const val BT2020_LUMA_G: Float = 0.6780f

/**
 * BT.2020's blue luminance weight.
 */
@InternalFilmstripApi
public const val BT2020_LUMA_B: Float = 0.0593f

/**
 * What Cb is scaled by on the way from blue minus luma to a BT.2020 NCL chroma channel.
 *
 * Follows from [BT2020_LUMA_B]: blue reaches its extreme at `1 - Kb` above luma, and the channel
 * spans plus or minus a half.
 */
@InternalFilmstripApi
public const val BT2020_CB_SCALE: Float = 2f * (1f - BT2020_LUMA_B)

/**
 * What Cr is scaled by, the red counterpart of [BT2020_CB_SCALE].
 */
@InternalFilmstripApi
public const val BT2020_CR_SCALE: Float = 2f * (1f - BT2020_LUMA_R)

/**
 * The BT.2020 non-constant luminance matrix from an R'G'B' signal to Y', Cb and Cr, one row per output.
 *
 * Each row holds the weights of red, green and blue in that order, and the rows come out as luma, Cb and Cr. Luma runs
 * from zero to one and each chroma channel spans plus or minus a half, before any scaling into codes. Built on
 * [BT2020_LUMA_R], its siblings and the chroma scales, so this and [BT2020_DECODE_ROWS] are the one place the matrix is
 * written.
 */
@InternalFilmstripApi
public val BT2020_ENCODE_ROWS: List<ChannelTriple> =
  run {
    val luma = doubleArrayOf(BT2020_LUMA_R.toDouble(), BT2020_LUMA_G.toDouble(), BT2020_LUMA_B.toDouble())
    listOf(
      ChannelTriple(luma),
      ChannelTriple(DoubleArray(3) { (primary(it, BLUE) - luma[it]) / BT2020_CB_SCALE }),
      ChannelTriple(DoubleArray(3) { (primary(it, RED) - luma[it]) / BT2020_CR_SCALE }),
    )
  }

/**
 * The inverse of [BT2020_ENCODE_ROWS], from Y', Cb and Cr back to an R'G'B' signal, one row per output.
 *
 * Each row holds the weights of luma, Cb and Cr in that order, and the rows come out as red, green and blue. Red and
 * blue are luma plus their chroma channel scaled back up, and green is what luma leaves once their shares of it are
 * taken out.
 */
@InternalFilmstripApi
public val BT2020_DECODE_ROWS: List<ChannelTriple> =
  run {
    val red = doubleArrayOf(1.0, 0.0, BT2020_CR_SCALE.toDouble())
    val blue = doubleArrayOf(1.0, BT2020_CB_SCALE.toDouble(), 0.0)
    val green =
      DoubleArray(3) { (primary(it, LUMA) - BT2020_LUMA_R * red[it] - BT2020_LUMA_B * blue[it]) / BT2020_LUMA_G }
    listOf(ChannelTriple(red), ChannelTriple(green), ChannelTriple(blue))
  }

/**
 * Three doubles, one per channel, which a caller reads by index but cannot change.
 *
 * Carries each row of [BT2020_ENCODE_ROWS] and [BT2020_DECODE_ROWS], and [BT2020_CHROMA_SHIFT], so the shared matrix
 * stays as core wrote it. The channel order is the one the holding declaration names.
 */
@InternalFilmstripApi
public class ChannelTriple internal constructor(
  private val values: DoubleArray,
) {
  /**
   * Reads the value for one channel.
   *
   * @param index The channel, from zero to two.
   */
  public operator fun get(index: Int): Double = values[index]
}

private const val RED = 0
private const val BLUE = 2
private const val LUMA = 0

private fun primary(
  channel: Int,
  of: Int,
): Double = if (channel == of) 1.0 else 0.0
