package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A Gaussian blur worked out for one frame width, which runs at a smaller size so its sigma stays cheap.
 *
 * Built by [BlurPlan.of], and read by every backend that draws a wide blur so they all blur by the same kernel.
 *
 * @property factor How many frame pixels one blurred pixel spans.
 * @property width The width in pixels the blur runs at.
 * @property sigmaPx The sigma in blurred pixels, at most [MAX_SIGMA_PX].
 * @property taps How many taps run each side of the centre, out to [SUPPORT_SIGMAS] sigma.
 * @property weights The weight of the centre and then of each tap out from it, which sum to 1 with each side counted.
 */
@InternalFilmstripApi
public class BlurPlan internal constructor(
  public val factor: Int,
  public val width: Int,
  public val sigmaPx: Double,
  public val taps: Int,
  public val weights: DoubleArray,
) {
  /**
   * The height in pixels the blur runs at, for a frame [heightPx] tall.
   */
  public fun heightOf(heightPx: Int): Int = max(1, heightPx / factor)

  /**
   * Builds the plan for a blur and holds the limits every plan stays inside.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * The largest sigma a plan runs at, in the pixels it blurs. A wider blur downscales until it fits.
     */
    public const val MAX_SIGMA_PX: Double = 8.0

    /**
     * How many sigmas out from the centre the taps reach.
     */
    public const val SUPPORT_SIGMAS: Double = 3.0

    /**
     * The most taps a plan runs each side of the centre, which a shader sizes its weight array by.
     */
    public const val MAX_TAPS: Int = 24

    /**
     * How to run a blur of [sigmaFraction] of the frame width on a frame [widthPx] wide.
     *
     * The frame is scaled down by a whole factor until the sigma is at most [MAX_SIGMA_PX] there, so the sigma as a
     * fraction of the frame stays the one asked for at any width.
     *
     * @throws IllegalArgumentException when [sigmaFraction] is not above 0.
     */
    public fun of(
      sigmaFraction: Double,
      widthPx: Int,
    ): BlurPlan {
      require(sigmaFraction > 0.0) { "A blur needs a sigma above 0, not $sigmaFraction." }
      val factor = max(1, ceil(sigmaFraction * widthPx / MAX_SIGMA_PX).toInt())
      val width = max(1, widthPx / factor)
      val sigma = sigmaFraction * width
      val taps = max(1, min(MAX_TAPS, ceil(SUPPORT_SIGMAS * sigma).toInt()))
      val raw = DoubleArray(taps + 1) { exp(-0.5 * (it / sigma) * (it / sigma)) }
      val total = raw[0] + 2.0 * raw.drop(1).sum()
      return BlurPlan(factor, width, sigma, taps, DoubleArray(taps + 1) { raw[it] / total })
    }
  }
}
