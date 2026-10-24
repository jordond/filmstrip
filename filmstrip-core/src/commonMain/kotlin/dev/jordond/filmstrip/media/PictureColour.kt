package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.ChromaFormat
import dev.jordond.filmstrip.internal.MAX_PROFILE_SIZE
import dev.jordond.filmstrip.internal.chooseColour
import dev.jordond.filmstrip.internal.decodedPngColour
import kotlin.math.abs

/**
 * How a picture describes the colour its stored channels stand for.
 *
 * An embedded ICC profile, a CICP code point set or a PNG gamma and chromaticities pair. Read one out of a picture
 * with [dev.jordond.filmstrip.internal.pictureColour], or take what a decoder carried with [fromDecodedProfile] or
 * [fromDecodedPng], then hand it unopened to [pictureLight] or [pictureSrgbCodes], which turn every kind into the
 * same evaluation.
 */
@InternalFilmstripApi
public sealed interface PictureColour {
  /**
   * Builds a description from what a decoder hands back, for a picture whose own container carries none that core
   * can read, such as a JPEG XL whose colour sits inside its codestream.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * Wraps the ICC profile a decoder carried for a picture whose container holds none, or returns null when the
     * profile does not describe the picture.
     *
     * A profile describes the picture when it parses as an RGB profile and the decode has three colour channels, or as
     * a grey profile and the decode has one. Any other colour space, a profile that does not parse, an empty profile
     * and one over 16 MiB return null.
     *
     * @param profile The profile bytes as the decoder handed them over.
     * @param channels How many colour channels the decode stores, alpha not counted.
     */
    @InternalFilmstripApi
    public fun fromDecodedProfile(
      profile: ByteArray,
      channels: Int,
    ): PictureColour? {
      if (profile.size !in 1..MAX_PROFILE_SIZE) return null
      return Icc(profile.copyOf()).takeIf { it.describes(channels) }
    }

    /**
     * Reads the colour of a picture a decoder wrote out as a PNG, from its `cICP` chunk and then its `iCCP` profile,
     * or returns null when it lands exactly as an untagged picture does.
     *
     * A decoder writes `sRGB`, `cHRM` and `gAMA` chunks from what it guessed about the source, so they are never read
     * here. Only `cICP` and `iCCP` carry the source's own colour. The first of the two that is understood and fits
     * [channels] wins. A `cICP` whose code points are unspecified defers to `iCCP`, and one whose code points are sRGB
     * or not supported lands the picture as untagged, the same rule [dev.jordond.filmstrip.internal.pictureColour]
     * follows.
     *
     * @param png The decoded PNG's bytes.
     * @param channels How many colour channels the source picture stores, alpha not counted, since a decoder may
     * write a grey picture out as RGB.
     */
    @InternalFilmstripApi
    public fun fromDecodedPng(
      png: ByteArray,
      channels: Int,
    ): PictureColour? = decodedPngColour(png)?.let { chooseColour(it.descriptions, channels) }
  }
}

/**
 * An ICC profile, kept as the bytes the picture or its decoder carried.
 */
internal class Icc(
  val profile: ByteArray,
) : PictureColour {
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
) : PictureColour

/**
 * A PNG `gAMA` value and `cHRM` chromaticities, either one missing when the picture carries only the other.
 *
 * [gamma] is the encoding exponent times 100000, as `gAMA` stores it. A missing [gamma] stands for the sRGB curve
 * and missing [chromaticities] for sRGB's primaries and white.
 */
internal data class PngGamma(
  val gamma: Int?,
  val chromaticities: PngChromaticities?,
) : PictureColour

/**
 * How a YCbCr coded picture's samples turn back into RGB, as an AVIF or HEIC `nclx` colour box records it or, without
 * one, as its codestream signals it, with what the codestream itself signals and how its samples are laid out.
 *
 * Applying it stays the decoder's job. A backend reads it to steer or check its decoder, so none parses the box or
 * the codestream itself. Read it with [dev.jordond.filmstrip.internal.pictureYcbcrCoding].
 *
 * The `nclx` box records [matrix] and [fullRange], which libheif and libavif follow. A picture without one lands with
 * its codestream's own, so [matrix] and [fullRange] are then [codestream]'s, a matrix it leaves out being 2 for
 * unspecified and a range it leaves out being limited. [codestream] is what a decoder that reads the HEVC sequence
 * parameter set or AV1 sequence header instead of the box sees, so a backend can tell when its decoder will land the
 * picture differently. When there is no `nclx` and the codestream does not read either, [matrix] is 2 and [fullRange]
 * limited as for a silent codestream, and [codestream] is null, so [colourKnown] is false and only [codec] says
 * anything.
 *
 * @property matrix The ITU-T H.273 matrix coefficients code point, such as 0 for identity, 1 for BT.709, 5 and 6 for
 * BT.601 and 9 for BT.2020.
 * @property fullRange Whether the samples span their bit depth's full range rather than the limited video range.
 * @property codec The codec the picture's primary item is coded with, [CodecKind.Hevc] for a HEIC, [CodecKind.Av1] for
 * an AVIF and [CodecKind.Other] for any other item type.
 * @property codestream The video signal of the first sequence parameter set in the primary item's `hvcC` for HEVC,
 * then of the first at the start of its item data when the `hvcC` holds none. For AV1 it is the colour config of the
 * first sequence header at the start of its item data, then of the first in its `av1C` when the data holds none. A
 * grid takes its first tile's. Null means the signal is unknown, when [codec] is neither, or no such unit is found or
 * the one taken does not parse. A field the unit leaves out is null, so a signal whose fields are all null means an
 * HEVC parameter set was read and signals nothing.
 * @property fromNclx Whether an `nclx` box recorded [matrix] and [fullRange], false when they come from [codestream].
 * @property bitDepth The bit depth of the luma samples, 8, 10 or 12 for AV1 and 8 to 15 for HEVC, as every sequence
 * parameter set or sequence header of the primary item and its tiles states it, in their `hvcC` or `av1C` and at the
 * start of their item data, and as those boxes state it too. Null when [codec] is neither, there is no such unit, or
 * any unit or box does not read or states another depth or layout than the rest.
 * @property chromaFormat How the chroma samples are laid out, as the same units and boxes state it and numbered the
 * way HEVC's `chroma_format_idc` is, 0 for monochrome, 1 for 4:2:0, 2 for 4:2:2 and 3 for 4:4:4. Null whenever
 * [bitDepth] is, which covers a layout AV1 has no number for. When it is set, so is [bitDepth].
 * @property primaries The ITU-T H.273 colour primaries code point beside [matrix], from the `nclx` when it records
 * [matrix] and from [codestream] otherwise, 2 for unspecified when that leaves it out.
 * @property transfer The ITU-T H.273 transfer characteristics code point beside [matrix], taken the way [primaries]
 * is.
 */
@InternalFilmstripApi
public data class YcbcrCoding(
  public val matrix: Int,
  public val fullRange: Boolean,
  public val codec: CodecKind,
  public val codestream: CodestreamSignal?,
  public val fromNclx: Boolean = true,
  public val bitDepth: Int? = null,
  public val chromaFormat: Int? = null,
  public val primaries: Int = codestream?.primaries ?: UNSPECIFIED_CODE_POINT,
  public val transfer: Int = codestream?.transfer ?: UNSPECIFIED_CODE_POINT,
) {
  /**
   * How the picture's samples turn back into red, green and blue, [ycbcrReading] of [matrix] beside [primaries] and
   * [transfer].
   *
   * A backend steers its decoder by this rather than by [matrix], since a matrix derived from the primaries and an
   * unspecified one only say how they read once resolved here.
   */
  public val reading: YcbcrReading = ycbcrReading(matrix, primaries, transfer)

  /**
   * Whether an AV1 or HEVC picture lands with the identity matrix 0 on samples not known to be 4:4:4 colour.
   *
   * AV1 and HEVC allow the identity matrix only on 4:4:4 samples, so such a picture is invalid and a backend refuses
   * it with [IDENTITY_NOT_444_REASON] before its decoder sees it. An unknown [chromaFormat] counts as not 4:4:4,
   * which covers a layout AV1 has no number for, a box or codestream that does not read and units or boxes that
   * disagree on the layout or depth. A 4:4:4 [chromaFormat] without a [bitDepth] counts as not 4:4:4 too, so a false
   * result on an AV1 or HEVC coding with the identity matrix means [bitDepth] is set. Always false for
   * [CodecKind.Other].
   */
  public val identityNot444: Boolean
    get() = matrix == IDENTITY_MATRIX && codec != CodecKind.Other && (!unsubsampled || bitDepth == null)

  /**
   * Whether [chromaFormat] says the picture is 4:4:4, with a chroma sample beside every luma sample.
   *
   * False when [chromaFormat] is null, so an unknown layout never counts as 4:4:4.
   */
  public val unsubsampled: Boolean
    get() = chromaFormat == ChromaFormat.YUV444

  /**
   * Why no backend may draw the picture, or null when one may.
   *
   * It is the [YcbcrReading.Refused.reason] of a [reading] core refuses, then [IDENTITY_NOT_444_REASON] when
   * [identityNot444] is true. A backend refuses a picture with it before its decoder sees it, and adds any limit of its
   * own after.
   */
  public val refusal: String?
    get() =
      when {
        reading is YcbcrReading.Refused -> reading.reason
        identityNot444 -> IDENTITY_NOT_444_REASON
        else -> null
      }

  /**
   * Whether [matrix] and [fullRange] say how the picture lands, true when an `nclx` box records them or [codestream]
   * reads.
   *
   * False when the picture has no `nclx` and its codestream does not read, so [matrix] and [fullRange] are only the
   * stand-ins for a silent codestream. A backend then leaves the conversion to its decoder rather than steering it
   * with them.
   */
  public val colourKnown: Boolean
    get() = fromNclx || codestream != null
}

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

private const val UNSPECIFIED_CODE_POINT = 2

/**
 * The video signal an HEVC sequence parameter set carries in its video usability information, or an AV1 sequence
 * header in its colour config, each field as the codestream states it.
 *
 * A field the codestream leaves out is null, and a decoder that reads the codestream applies its own default in its
 * place.
 *
 * @property primaries The colour primaries code point, or null without a colour description.
 * @property transfer The transfer characteristics code point, or null without a colour description.
 * @property matrix The matrix coefficients code point, or null without a colour description.
 * @property fullRange The full range flag, or null when an HEVC parameter set carries no video signal type. An AV1
 * sequence header always gives one.
 */
@InternalFilmstripApi
public data class CodestreamSignal(
  public val primaries: Int?,
  public val transfer: Int?,
  public val matrix: Int?,
  public val fullRange: Boolean?,
)

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
 * Whether this description fits a picture storing [channels] colour channels, or one whose count is unknown when
 * [channels] is null.
 *
 * An ICC profile fits only when it parses, and then only its own colour space, three channels for RGB and one for
 * grey. CICP and PNG gamma describe a grey picture as well as a colour one.
 */
internal fun PictureColour.describes(channels: Int?): Boolean =
  when (this) {
    is Icc -> parsed?.let { channels == null || channels == if (it is GreyProfile) 1 else 3 } == true
    is Cicp, is PngGamma -> channels == null || channels == 1 || channels == 3
  }

/**
 * Whether this description is sRGB itself, the CICP code points for sRGB or PNG gamma at PNG-3's sRGB equivalence, so
 * the picture lands exactly as an untagged one does.
 *
 * An ICC profile is never sRGB here, since only its parsed curves and colorants can say so.
 */
internal val PictureColour.isSrgb: Boolean
  get() =
    when (this) {
      is Icc -> {
        false
      }
      is Cicp -> {
        isSupported && primaries == SRGB_PRIMARIES && transfer == SRGB_TRANSFER
      }
      is PngGamma -> {
        (gamma == null || abs(gamma - SRGB_PNG_GAMMA) <= 1) &&
          (chromaticities == null || chromaticities == REC709_CHROMATICITIES)
      }
    }

/**
 * How this description's colour is evaluated, or null when the picture lands exactly as an untagged one does.
 *
 * Null covers a description that is sRGB, an ICC profile that does not parse as an RGB or grey profile, and CICP code
 * points or chromaticities this evaluation does not support.
 */
internal fun PictureColour.evaluator(): ColourEvaluation? =
  when (this) {
    is Icc -> {
      parsed?.let { ColourEvaluation(it, transfer = null) }
    }
    is Cicp -> {
      if (isSrgb || !isSupported) {
        null
      } else {
        describedProfile(cicpChromaticities(primaries), cicpCurve(transfer))?.let { ColourEvaluation(it, hdrTransfer) }
      }
    }
    is PngGamma -> {
      if (isSrgb || gamma?.let { it <= 0 } == true) {
        null
      } else {
        describedProfile(chromaticities?.values ?: SRGB_CHROMATICITIES, gamma?.let { pngCurve(it) } ?: SRGB_CURVE)
          ?.let { ColourEvaluation(it, transfer = null) }
      }
    }
  }

/**
 * The [profile] a description's colour runs through, and the HDR [transfer] its curves decode, or null when its light
 * tops out at reference white.
 *
 * A PQ profile's light is relative to [HDR_REFERENCE_WHITE_NITS] and reaches past one. An HLG profile's is scene light,
 * which still needs the opto-optical transfer to become display light.
 */
internal class ColourEvaluation(
  val profile: IccProfile,
  val transfer: HdrTransfer?,
)

/**
 * A matrix and curves profile with [curve] on every channel and colorants built from [chromaticities], adapted to the
 * D50 connection space with Bradford from their own white, or null when either is missing or they span no colour
 * space.
 */
internal fun describedProfile(
  chromaticities: DoubleArray?,
  curve: IccCurve?,
): IccProfile? {
  if (chromaticities == null || curve == null) return null
  val toXyz = rgbToXyz(chromaticities) ?: return null
  val white = xyToXyz(chromaticities[6], chromaticities[7])
  val d50 = multiply(bradford(white, PCS_WHITE), toXyz)
  if (d50.any { !it.isFinite() }) return null
  return MatrixTrcProfile(DoubleArray(9) { d50[(it % 3) * 3 + it / 3] }, List(3) { curve })
}

/**
 * The colour channels an ICC profile's header names, three for RGB and one for grey, or null for any other colour
 * space or a profile too short to name one.
 */
internal fun iccChannels(profile: ByteArray): Int? {
  if (profile.size < COLOUR_SPACE_END) return null
  val space = profile.copyOfRange(COLOUR_SPACE_OFFSET, COLOUR_SPACE_END).decodeToString()
  return when (space) {
    "RGB " -> 3
    "GRAY" -> 1
    else -> null
  }
}

// PNG's gAMA stores the encoding exponent, so the curve to light raises to its inverse.
private fun pngCurve(gamma: Int): IccCurve = IccCurve.Gamma(PNG_UNIT / gamma)

internal val REC709_CHROMATICITIES = PngChromaticities(31270, 32900, 64000, 33000, 30000, 60000, 15000, 6000)

private val SRGB_CHROMATICITIES = REC709_CHROMATICITIES.values

private const val PNG_UNIT = 100000.0
private const val SRGB_PNG_GAMMA = 45455
private const val COLOUR_SPACE_OFFSET = 16
private const val COLOUR_SPACE_END = 20
