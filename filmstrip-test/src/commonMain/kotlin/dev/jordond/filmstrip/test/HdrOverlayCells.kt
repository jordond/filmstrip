package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.hlgSceneFromNits
import dev.jordond.filmstrip.color.hlgSignalFromScene
import dev.jordond.filmstrip.color.nitsFromSignal
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.abs

/**
 * The overlay cells every backend's HDR overlay test draws and reads back, each one overlay color drawn at one opacity
 * over one fill.
 *
 * A backend iterates [CELLS] to build its grid and predicts each reading from [Cell.nits]. The grid crosses white,
 * [AuthoredColors.MID_GREY] and [AuthoredColors.MID_GREEN] over a mid grey fill with full, half and quarter opacity,
 * and adds white and mid grey over [BLACK] at half and a quarter. [BLENDS] holds the cells whose reading depends on how
 * the overlay is blended, and every cell carries the two wrong blends a backend could land on instead of the rule.
 */
@InternalFilmstripApi
public object HdrOverlayCells {
  /**
   * An opaque white, the brightest color an overlay can author.
   *
   * It lands at reference white on an HDR grade, as far above a mid grey fill as an authored color goes.
   */
  public const val WHITE: Int = 0xFFFFFFFF.toInt()

  /**
   * An opaque black, the fill the black cells draw over.
   *
   * It carries no light, so an overlay blended over it lands on its own light scaled by its opacity.
   */
  public const val BLACK: Int = 0xFF000000.toInt()

  /**
   * One [overlay] color drawn at [opacity] over a fill of [background].
   *
   * Both colors are packed sRGB with alpha ignored, and [opacity] is the overlay's own opacity applied to fully opaque
   * texels. Besides where the cell lands, it gives where a signal blend and an sRGB blend of it land.
   */
  @InternalFilmstripApi
  public data class Cell(
    val overlay: Int,
    val background: Int,
    val opacity: Float,
  ) {
    /**
     * Whether a reading of this cell depends on how the overlay is blended.
     *
     * An opaque overlay replaces the fill, and an overlay the same color as the fill lands on it at any opacity, so
     * neither one is a blend. The colors are compared on red, green and blue alone.
     */
    public val isBlend: Boolean
      get() = opacity < 1f && (overlay and RGB) != (background and RGB)

    /**
     * Where this cell lands on an HDR grade, from [OverlayPixels.hdrSourceOver] over the fill's [authoredHdrNits].
     *
     * @return Linear BT.2020 red, green and blue in cd/m2, in that order.
     */
    public fun nits(): FloatArray = OverlayPixels.hdrSourceOver(authoredHdrNits(background), overlay, opacity)

    /**
     * The ten-bit codes a frame on [transfer] stores where this cell lands.
     *
     * [nits] encoded through [signalFromNits] and then [tenBitCodesFromSignal].
     *
     * @return Luma, Cb and Cr in that order.
     */
    public fun codes(transfer: HdrTransfer): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(nits()))

    /**
     * Where this cell lands when the overlay is blended in the [transfer]'s encoded signal instead of in light.
     *
     * The overlay's and the fill's [authoredHdrNits] are encoded through [signalFromNits], mixed there by [opacity],
     * and the mix is decoded back to display light through [nitsFromSignal].
     *
     * @return Linear BT.2020 red, green and blue in cd/m2, in that order.
     */
    public fun signalBlendNits(transfer: HdrTransfer): FloatArray = transfer.nitsFromSignal(signalBlend(transfer))

    /**
     * The ten-bit codes a frame on [transfer] stores when the overlay is blended in the encoded signal.
     *
     * The mixed signal [signalBlendNits] decodes goes straight through [tenBitCodesFromSignal].
     *
     * @return Luma, Cb and Cr in that order.
     */
    public fun signalBlendCodes(transfer: HdrTransfer): IntArray = tenBitCodesFromSignal(signalBlend(transfer))

    /**
     * Where this cell lands when the overlay is blended on the eight-bit sRGB codes before they are mapped to light.
     *
     * Each channel is mixed through [OverlayPixels.sourceOver] and the mixed color is mapped through
     * [authoredHdrNits], so the light is the same on either transfer.
     *
     * @return Linear BT.2020 red, green and blue in cd/m2, in that order.
     */
    public fun srgbBlendNits(): FloatArray {
      val (red, green, blue) =
        AuthoredColors.channelsOf(background).zip(AuthoredColors.channelsOf(overlay)) { under, over ->
          OverlayPixels.sourceOver(under, over, opacity) / AuthoredColors.FULL_SCALE
        }
      return authoredHdrNits(red, green, blue)
    }

    /**
     * The ten-bit codes a frame on [transfer] stores when the overlay is blended on the eight-bit sRGB codes.
     *
     * [srgbBlendNits] encoded through [signalFromNits] and then [tenBitCodesFromSignal].
     *
     * @return Luma, Cb and Cr in that order.
     */
    public fun srgbBlendCodes(transfer: HdrTransfer): IntArray =
      tenBitCodesFromSignal(transfer.signalFromNits(srgbBlendNits()))

    /**
     * How far, in ten-bit codes, the nearer of the two wrong blends lands from [codes] on [transfer].
     *
     * A wrong blend's miss is its largest gap on any one of luma, Cb and Cr. This is the smaller of the misses of
     * [signalBlendCodes] and [srgbBlendCodes], and a cell that is not [isBlend] misses by zero.
     */
    public fun wrongBlendMiss(transfer: HdrTransfer): Int {
      val rule = codes(transfer)
      return minOf(codeMiss(rule, signalBlendCodes(transfer)), codeMiss(rule, srgbBlendCodes(transfer)))
    }

    /**
     * Where this cell lands on an HLG grade when the overlay is blended in scene light instead of display light.
     *
     * Both colors go to scene light through [hlgSceneFromNits] of their [authoredHdrNits], mix there by [opacity], and
     * the mix is encoded with [hlgSignalFromScene]. A backend that holds an HLG frame in scene light blends this way.
     *
     * @return The HLG signal for red, green and blue, each from zero to one.
     */
    public fun hlgSceneLightSignal(): FloatArray {
      val over = hlgSceneFromNits(authoredHdrNits(overlay))
      val under = hlgSceneFromNits(authoredHdrNits(background))
      return FloatArray(3) { hlgSignalFromScene(opacity * over[it] + (1f - opacity) * under[it]) }
    }

    private fun signalBlend(transfer: HdrTransfer): FloatArray {
      val over = transfer.signalFromNits(authoredHdrNits(overlay))
      val under = transfer.signalFromNits(authoredHdrNits(background))
      return FloatArray(3) { opacity * over[it] + (1f - opacity) * under[it] }
    }
  }

  /**
   * Every cell an HDR overlay grid reads, the mid grey fill's nine first and then the four over [BLACK].
   *
   * The opaque cells pin where a fill of the overlay's color lands, and the rest read the middle of the opacity ramp.
   */
  public val CELLS: List<Cell> =
    listOf(1f, HALF, QUARTER).flatMap { opacity ->
      listOf(WHITE, AuthoredColors.MID_GREY, AuthoredColors.MID_GREEN).map { overlay ->
        Cell(overlay, AuthoredColors.MID_GREY, opacity)
      }
    } +
      listOf(HALF, QUARTER).flatMap { opacity ->
        listOf(WHITE, AuthoredColors.MID_GREY).map { overlay -> Cell(overlay, BLACK, opacity) }
      }

  /**
   * The cells of [CELLS] that are [Cell.isBlend], where a backend blending the wrong way reads off its prediction.
   *
   * The opaque cells and the grey over grey cells drop out, since every blend lands them in the same place.
   */
  public val BLENDS: List<Cell> = CELLS.filter { it.isBlend }

  /**
   * The smallest miss, in ten-bit codes, of a wrong blend on any [BLENDS] cell on either transfer.
   *
   * It is the smallest [Cell.wrongBlendMiss] over every blend cell on PQ and on HLG, so a wrong blend of any of them
   * lands at least this far from the rule on one of luma, Cb and Cr.
   */
  public val SMALLEST_WRONG_BLEND_MISS: Int =
    HdrTransfer.entries.minOf { transfer -> BLENDS.minOf { it.wrongBlendMiss(transfer) } }

  /**
   * The widest bound, in ten-bit codes per channel, a backend may hold a reading of a [BLENDS] cell to.
   *
   * It is the largest whole code whose double is below [SMALLEST_WRONG_BLEND_MISS]. A reading within this bound of the
   * rule and one within it of a wrong blend cannot meet, so a reading of a wrong blend falls outside the bound on every
   * blend cell, on both PQ and HLG. A wrong blend mixes the encoded signals, or mixes the eight-bit sRGB codes before
   * mapping them. The bound is below zero if any blend cell cannot tell a wrong blend from the rule.
   */
  public val MAX_BLEND_TOLERANCE: Int = (SMALLEST_WRONG_BLEND_MISS - 1).floorDiv(2)

  private fun codeMiss(
    codes: IntArray,
    other: IntArray,
  ): Int = codes.indices.maxOf { abs(codes[it] - other[it]) }

  private const val RGB = 0xFFFFFF
  private const val HALF = 0.5f
  private const val QUARTER = 0.25f
}
