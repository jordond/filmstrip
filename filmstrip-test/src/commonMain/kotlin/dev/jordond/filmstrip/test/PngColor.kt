package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The white point and primaries a PNG `cHRM` chunk holds, each CIE x and y as stored, times 100000.
 */
@InternalFilmstripApi
public class Chromaticities(
  public val whiteX: Int,
  public val whiteY: Int,
  public val redX: Int,
  public val redY: Int,
  public val greenX: Int,
  public val greenY: Int,
  public val blueX: Int,
  public val blueY: Int,
) {
  /**
   * The eight values in the order a `cHRM` chunk stores them, white first, then red, green and blue.
   */
  public val values: List<Int> get() = listOf(whiteX, whiteY, redX, redY, greenX, greenY, blueX, blueY)

  @InternalFilmstripApi
  public companion object {
    /**
     * The Rec. 709 primaries with a D65 white, the chromaticities of sRGB.
     */
    public val REC_709: Chromaticities = Chromaticities(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)

    /**
     * The Display P3 primaries with a D65 white.
     */
    public val DISPLAY_P3: Chromaticities = Chromaticities(31270, 32900, 68000, 32000, 26500, 69000, 15000, 6000)
  }
}

/**
 * The color chunks other than `iCCP` that [OverlayPixels.pngOf] writes ahead of the image data.
 *
 * Each one left null is not written.
 *
 * @property cicp The code points of a `cICP` chunk.
 * @property gamma A `gAMA` chunk's value, the encoding gamma times 100000, so 45455 for 1/2.2.
 * @property chromaticities The white point and primaries of a `cHRM` chunk.
 * @property srgbIntent The rendering intent of an `sRGB` chunk, from 0 to 3.
 */
@InternalFilmstripApi
public class PngColor(
  public val cicp: CicpTag? = null,
  public val gamma: Int? = null,
  public val chromaticities: Chromaticities? = null,
  public val srgbIntent: Int? = null,
)
