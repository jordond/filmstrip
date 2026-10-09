package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.linearToSrgb
import dev.jordond.filmstrip.media.srgbToLinear
import kotlin.math.abs
import kotlin.math.pow

/**
 * The authored colours every backend's SDR pins read back, and where a colour-managed backend would
 * write them instead.
 *
 * An authored colour is a fill colour, a text or plate colour, or an overlay image's pixels. It lands
 * in an SDR frame as its own codes. A colour-managed pipeline matches it in one of two directions.
 * Read as sRGB and written in the frame's 1/2.4 power encoding, a mid grey authored at 128 moves to
 * about 134.6, which is [colourManaged]. Read in the frame's encoding and written as sRGB, it moves
 * to about 121, which is [colourManagedFromFrame]. [TOLERANCE] is how far a reading may sit from its
 * own codes while still being told apart from either.
 */
@InternalFilmstripApi
public object AuthoredColours {
  /**
   * A mid grey, with every channel where colour management moves a code by a visible step.
   */
  public const val MID_GREY: Int = 0xFF808080.toInt()

  /**
   * A green with its green channel at mid grey and the others low, so a backend that swaps or
   * blends channels cannot read as right.
   */
  public const val MID_GREEN: Int = 0xFF308020.toInt()

  /**
   * Every colour the pins author, each carrying at least one channel in [MID_RANGE].
   */
  public val COLOURS: List<Int> = listOf(MID_GREY, MID_GREEN)

  /**
   * The codes [TOLERANCE] is measured over. Colour management leaves both ends of the range where
   * they were, so a channel near either end reads the same whether it was managed or not.
   */
  public val MID_RANGE: IntRange = 64..192

  /**
   * Where a colour-managed backend writes a channel authored at [code], as an eight-bit code. The code
   * is decoded as sRGB into linear light and that light is encoded at the 1/2.4 power.
   */
  public fun colourManaged(code: Int): Float = srgbToLinear(code / FULL_SCALE).pow(1f / MANAGED_GAMMA) * FULL_SCALE

  /**
   * Where a backend matching the other way writes a channel authored at [code], as an eight-bit code.
   * The code is decoded at the 2.4 power into linear light and that light is encoded as sRGB.
   */
  public fun colourManagedFromFrame(code: Int): Float =
    linearToSrgb((code / FULL_SCALE).pow(MANAGED_GAMMA)) * FULL_SCALE

  /**
   * How far a channel read back may sit from its authored code, in code values. It is under half the
   * smallest gap, over every [MID_RANGE] channel of [COLOURS] and both directions, between an authored
   * code and [colourManaged] or [colourManagedFromFrame] of it, so a reading inside it cannot be a
   * managed one.
   */
  public val TOLERANCE: Float =
    COLOURS
      .flatMap { channelsOf(it) }
      .filter { it in MID_RANGE }
      .flatMap { code -> listOf(abs(colourManaged(code) - code), abs(colourManagedFromFrame(code) - code)) }
      .min() * TOLERANCE_SHARE

  /**
   * The red, green and blue codes of [argb], in that order.
   */
  public fun channelsOf(argb: Int): List<Int> = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)

  /**
   * The largest eight-bit code, as a float.
   *
   * Dividing a code by it gives that channel's sRGB signal from zero to one.
   */
  internal const val FULL_SCALE = 255f

  private const val TOLERANCE_SHARE = 0.45f
  private const val MANAGED_GAMMA = 2.4f
}
