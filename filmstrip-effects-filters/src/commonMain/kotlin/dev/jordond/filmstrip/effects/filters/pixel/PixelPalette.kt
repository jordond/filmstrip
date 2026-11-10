package dev.jordond.filmstrip.effects.filters.pixel

/**
 * The colours a [Pixel] look snaps every cell to.
 *
 * Each cell ends on the entry nearest its own colour, measured on SDR codes, and the first entry wins a tie. The
 * palette keeps its own copy of the colours, so changing the list it was built from later changes nothing here.
 *
 * @param colors Packed ARGB as `0xAARRGGBB`, from [MIN_COLORS] to [MAX_COLORS] of them. Alpha is ignored, so
 * `0xRRGGBB` reads the same.
 * @throws IllegalArgumentException when [colors] holds fewer than [MIN_COLORS] or more than [MAX_COLORS].
 */
public class PixelPalette(
  colors: List<Int>,
) {
  /**
   * The colours this palette snaps to, in the order they were given.
   */
  public val colors: List<Int> = colors.toList()

  init {
    require(this.colors.size in MIN_COLORS..MAX_COLORS) {
      "A pixel palette holds $MIN_COLORS to $MAX_COLORS colors, not ${this.colors.size}."
    }
  }

  override fun equals(other: Any?): Boolean = this === other || (other is PixelPalette && colors == other.colors)

  override fun hashCode(): Int = colors.hashCode()

  override fun toString(): String = "PixelPalette(colors=$colors)"

  public companion object {
    /**
     * The fewest colours a palette holds.
     */
    public const val MIN_COLORS: Int = 2

    /**
     * The most colours a palette holds, which every backend searches one entry after another.
     */
    public const val MAX_COLORS: Int = 16

    /**
     * The four greens of the original handheld's screen, darkest first.
     */
    public val GameBoy: PixelPalette = PixelPalette(listOf(0x0F380F, 0x306230, 0x8BAC0F, 0x9BBC0F))

    /**
     * CGA palette 1 at high intensity, which is black, light cyan, light magenta and white.
     */
    public val Cga: PixelPalette = PixelPalette(listOf(0x000000, 0x55FFFF, 0xFF55FF, 0xFFFFFF))
  }
}
