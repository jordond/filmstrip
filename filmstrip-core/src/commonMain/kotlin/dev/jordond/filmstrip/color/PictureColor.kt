package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.icc.IccProfile
import dev.jordond.filmstrip.color.icc.parseIccProfile

/**
 * How a picture describes the color its stored channels stand for.
 *
 * An embedded ICC profile, a CICP code point set or a PNG gamma and chromaticities pair. Read one out of a picture
 * with [dev.jordond.filmstrip.picture.pictureColor], or take what a decoder carried with
 * [dev.jordond.filmstrip.picture.pictureColorFromDecodedProfile] or
 * [dev.jordond.filmstrip.picture.pictureColorFromDecodedPng], then hand it unopened to [pictureLight] or
 * [pictureSrgbCodes], which turn every kind into the same evaluation.
 */
@InternalFilmstripApi
public sealed interface PictureColor

/**
 * An ICC profile, kept as the bytes the picture or its decoder carried.
 */
internal class Icc(
  val profile: ByteArray,
) : PictureColor {
  /**
   * The parsed profile, or null when [profile] does not parse as an RGB or grey profile.
   */
  val parsed: IccProfile? by lazy { parseIccProfile(profile) }
}

/**
 * ITU-T H.273 code points, as an ISOBMFF `colr` box of type `nclx` or a PNG `cICP` chunk carries them.
 *
 * Read whether or not [isSupported] holds, so a picture whose code points are understood but not supported lands as
 * untagged instead of falling through to a later description. Code points that are [isUnspecified] say nothing, so
 * they do fall through.
 */
internal data class Cicp(
  val primaries: Int,
  val transfer: Int,
  val matrix: Int,
  val fullRange: Boolean,
) : PictureColor

/**
 * A PNG `gAMA` value and `cHRM` chromaticities, either one missing when the picture carries only the other.
 *
 * [gamma] is the encoding exponent times 100000, as `gAMA` stores it. A missing [gamma] stands for the sRGB curve
 * and missing [chromaticities] for sRGB's primaries and white.
 */
internal data class PngGamma(
  val gamma: Int?,
  val chromaticities: PngChromaticities?,
) : PictureColor

/**
 * The ITU-T H.273 matrix coefficients code point for identity, where the luma, blue difference and red difference
 * samples hold green, blue and red as they are.
 */
@InternalFilmstripApi
public const val IDENTITY_MATRIX: Int = 0

/**
 * Why a backend refuses a picture whose [YcbcrCoding.identityNot444] is true.
 */
@InternalFilmstripApi
public const val IDENTITY_NOT_444_REASON: String =
  "The picture names the identity matrix on samples not known to be 4:4:4, the only ones AV1 and HEVC allow it on."

/**
 * The ITU-T H.273 matrix coefficients code point a YCbCr picture whose [YcbcrCoding.matrix] is 2, unspecified, decodes
 * with.
 *
 * It is 6, BT.601, the matrix libheif and the measured HEIC decoders use in its place.
 */
@InternalFilmstripApi
public const val UNSPECIFIED_MATRIX_DECODES_AS: Int = 6

/**
 * A PNG `cHRM` chunk's white, red, green and blue chromaticities, x then y, each times 100000 as the chunk stores it.
 */
internal data class PngChromaticities(
  val whiteX: Int,
  val whiteY: Int,
  val redX: Int,
  val redY: Int,
  val greenX: Int,
  val greenY: Int,
  val blueX: Int,
  val blueY: Int,
) {
  /**
   * Red, green, blue and then the white, x then y, in the order [rgbToXyz] reads them.
   */
  val values: DoubleArray =
    doubleArrayOf(
      redX / PNG_UNIT,
      redY / PNG_UNIT,
      greenX / PNG_UNIT,
      greenY / PNG_UNIT,
      blueX / PNG_UNIT,
      blueY / PNG_UNIT,
      whiteX / PNG_UNIT,
      whiteY / PNG_UNIT,
    )
}

/**
 * The color channels an ICC profile's header names, three for RGB and one for grey, or null for any other color
 * space or a profile too short to name one.
 */
internal fun iccChannels(profile: ByteArray): Int? {
  if (profile.size < COLOR_SPACE_END) return null
  val space = profile.copyOfRange(COLOR_SPACE_OFFSET, COLOR_SPACE_END).decodeToString()
  return when (space) {
    "RGB " -> 3
    "GRAY" -> 1
    else -> null
  }
}

internal val REC709_CHROMATICITIES = PngChromaticities(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)

internal const val PNG_UNIT = 100000.0

private const val COLOR_SPACE_OFFSET = 16
private const val COLOR_SPACE_END = 20
