package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.abs

/**
 * A 3D colour table parsed from a `.cube` file, which every backend draws through trilinear interpolation.
 *
 * The table maps SDR codes to SDR codes over a domain of `0f` to `1f`. Entries are held in `.cube` order, with red
 * changing fastest and blue slowest. Two tables with the same entries are equal, and [hashCode] is worked out once, so
 * comparing tables per frame does not walk their entries.
 *
 * @property size How many grid points the table has along each axis.
 */
public class CubeLut private constructor(
  public val size: Int,
  internal val entries: FloatArray,
) {
  private val contentHash: Int = entries.contentHashCode()

  /**
   * Whether this table leaves every code where it is, within what its 16 bit atlas can tell apart.
   */
  public val isIdentity: Boolean = isIdentity(size, entries)

  /**
   * The colour this table maps a red, green and blue code to, in `0f..1f` and read between grid points trilinearly.
   *
   * A code outside `0f..1f` reads the grid's edge. This is the figure every backend draws.
   *
   * @return Red, green and blue.
   */
  public fun at(
    red: Float,
    green: Float,
    blue: Float,
  ): FloatArray {
    val out = DoubleArray(CHANNELS)
    trilinear(size, entries, red.toDouble(), green.toDouble(), blue.toDouble(), out)
    return FloatArray(CHANNELS) { out[it].toFloat() }
  }

  /**
   * This table mixed with the identity table entry by entry, at [intensity] from `0f` which is identity to `1f`.
   *
   * Identity is linear in each code, so a trilinear read of the result is the same mix of a trilinear read of each.
   *
   * @throws IllegalArgumentException when [intensity] is outside `0f..1f`.
   */
  @InternalFilmstripApi
  public fun blended(intensity: Float): CubeLut {
    require(intensity in 0f..1f) { "A LUT intensity has to be within 0 to 1, not $intensity." }
    if (intensity == 1f) return this
    val last = (size - 1).toFloat()
    val mixed = FloatArray(entries.size)
    forEachEntry(size) { index, red, green, blue ->
      val at = index * CHANNELS
      mixed[at] = red / last + (entries[at] - red / last) * intensity
      mixed[at + 1] = green / last + (entries[at + 1] - green / last) * intensity
      mixed[at + 2] = blue / last + (entries[at + 2] - blue / last) * intensity
    }
    return CubeLut(size, mixed)
  }

  override fun equals(other: Any?): Boolean =
    this === other ||
      (
        other is CubeLut &&
          size == other.size &&
          contentHash == other.contentHash &&
          entries.contentEquals(other.entries)
      )

  override fun hashCode(): Int = contentHash

  override fun toString(): String = "CubeLut(size=$size)"

  public companion object {
    /**
     * The smallest grid a `.cube` table can hold, by the format's own rule.
     */
    public const val MIN_SIZE: Int = 2

    /**
     * The largest grid [parse] takes, which is well past the 65 points a grading tool exports.
     */
    public const val MAX_SIZE: Int = 129

    /**
     * Parses the text of a `.cube` file.
     *
     * Comments, a `TITLE`, and `DOMAIN_MIN` and `DOMAIN_MAX` are read, or `LUT_3D_INPUT_RANGE` in their place. A domain
     * other than `0` to `1` is applied by reading the file's table onto a grid over `0` to `1`, of at least 65 points.
     * An entry outside `0` to `1` is held at the end it passed, since an SDR code stops there. A line can end in LF,
     * CRLF or a lone CR.
     *
     * The parse runs on the calling thread and takes time in proportion to the text, so a large file belongs off the
     * main thread.
     *
     * @throws IllegalArgumentException when the text holds a 1D table, a size outside [MIN_SIZE] to [MAX_SIZE], a line
     * that is neither a known keyword nor three finite numbers, the wrong number of entries, or more than
     * [MAX_CUBE_CHARS] characters.
     */
    public fun parse(text: String): CubeLut = parse(text, MAX_CUBE_CHARS)

    internal fun parse(
      text: String,
      maxChars: Int,
    ): CubeLut {
      val parsed = CubeParser(text, maxChars).parse()
      return CubeLut(parsed.size, parsed.entries)
    }

    /**
     * The table that leaves every code where it is, at [size] grid points along each axis.
     *
     * @throws IllegalArgumentException when [size] is outside [MIN_SIZE] to [MAX_SIZE].
     */
    public fun identity(size: Int): CubeLut {
      require(size in MIN_SIZE..MAX_SIZE) { "A LUT size has to be within $MIN_SIZE to $MAX_SIZE, not $size." }
      val last = (size - 1).toFloat()
      val entries = FloatArray(size * size * size * CHANNELS)
      forEachEntry(size) { index, red, green, blue ->
        val at = index * CHANNELS
        entries[at] = red / last
        entries[at + 1] = green / last
        entries[at + 2] = blue / last
      }
      return CubeLut(size, entries)
    }
  }
}

/**
 * The most characters [CubeLut.parse] reads, which fits a [CubeLut.MAX_SIZE] table at 64 characters a row.
 */
public const val MAX_CUBE_CHARS: Int =
  CubeLut.MAX_SIZE * CubeLut.MAX_SIZE * CubeLut.MAX_SIZE * 64

internal const val CHANNELS = 3

// Half a step of the atlas's 16 bits, the most a table can differ from identity and draw the same.
private const val IDENTITY_TOLERANCE = 0.5f / 65535f

/**
 * Reads [entries] of a [size] grid trilinearly at a colour, into [out]. Each code is held to `0..1` first.
 */
internal fun trilinear(
  size: Int,
  entries: FloatArray,
  red: Double,
  green: Double,
  blue: Double,
  out: DoubleArray,
) {
  val last = size - 1
  val x = red.coerceIn(0.0, 1.0) * last
  val y = green.coerceIn(0.0, 1.0) * last
  val z = blue.coerceIn(0.0, 1.0) * last
  val x0 = x.toInt().coerceAtMost(last - 1)
  val y0 = y.toInt().coerceAtMost(last - 1)
  val z0 = z.toInt().coerceAtMost(last - 1)
  val fx = x - x0
  val fy = y - y0
  val fz = z - z0
  val stepY = size * CHANNELS
  val stepZ = size * size * CHANNELS
  val origin = (z0 * size * size + y0 * size + x0) * CHANNELS
  for (channel in 0 until CHANNELS) {
    val near = origin + channel
    val far = near + stepZ
    val nearLow = lerp(entries[near], entries[near + CHANNELS], fx)
    val nearHigh = lerp(entries[near + stepY], entries[near + stepY + CHANNELS], fx)
    val farLow = lerp(entries[far], entries[far + CHANNELS], fx)
    val farHigh = lerp(entries[far + stepY], entries[far + stepY + CHANNELS], fx)
    val nearMix = nearLow + (nearHigh - nearLow) * fy
    val farMix = farLow + (farHigh - farLow) * fy
    out[channel] = nearMix + (farMix - nearMix) * fz
  }
}

/**
 * Calls [action] with each entry's index and grid position, in `.cube` order.
 */
internal inline fun forEachEntry(
  size: Int,
  action: (index: Int, red: Int, green: Int, blue: Int) -> Unit,
) {
  var index = 0
  for (blue in 0 until size) {
    for (green in 0 until size) {
      for (red in 0 until size) {
        action(index++, red, green, blue)
      }
    }
  }
}

private fun lerp(
  from: Float,
  to: Float,
  by: Double,
): Double = from + (to.toDouble() - from) * by

private fun isIdentity(
  size: Int,
  entries: FloatArray,
): Boolean {
  val last = (size - 1).toFloat()
  forEachEntry(size) { index, red, green, blue ->
    val at = index * CHANNELS
    if (
      abs(entries[at] - red / last) > IDENTITY_TOLERANCE ||
      abs(entries[at + 1] - green / last) > IDENTITY_TOLERANCE ||
      abs(entries[at + 2] - blue / last) > IDENTITY_TOLERANCE
    ) {
      return false
    }
  }
  return true
}
