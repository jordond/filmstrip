package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.CodecKind

/**
 * How a YCbCr coded picture's samples turn back into RGB, as an AVIF or HEIC `nclx` color box records it or, without
 * one, as its codestream signals it, with what the codestream itself signals and how its samples are laid out.
 *
 * Applying it stays the decoder's job. A backend reads it to steer or check its decoder, so none parses the box or
 * the codestream itself. Read it with [dev.jordond.filmstrip.picture.pictureYcbcrCoding].
 *
 * The `nclx` box records [matrix] and [fullRange], which libheif and libavif follow. A picture without one lands with
 * its codestream's own, so [matrix] and [fullRange] are then [codestream]'s, a matrix it leaves out being 2 for
 * unspecified and a range it leaves out being limited. [codestream] is what a decoder that reads the HEVC sequence
 * parameter set or AV1 sequence header instead of the box sees, so a backend can tell when its decoder will land the
 * picture differently. When there is no `nclx` and the codestream does not read either, [matrix] is 2 and [fullRange]
 * limited as for a silent codestream, and [codestream] is null, so [colorKnown] is false and only [codec] says
 * anything.
 *
 * @property matrix The ITU-T H.273 matrix coefficients code point, such as 0 for identity, 1 for BT.709, 5 and 6 for
 * BT.601 and 9 for BT.2020.
 * @property fullRange Whether the samples span their bit depth's full range rather than the limited video range.
 * @property codec The codec the picture's primary item is coded with, [CodecKind.Hevc] for a HEIC, [CodecKind.Av1] for
 * an AVIF and [CodecKind.Other] for any other item type.
 * @property codestream The video signal of the first sequence parameter set in the primary item's `hvcC` for HEVC,
 * then of the first at the start of its item data when the `hvcC` holds none. For AV1 it is the color config of the
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
 * @property primaries The ITU-T H.273 color primaries code point beside [matrix], from the `nclx` when it records
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
   * Whether an AV1 or HEVC picture lands with the identity matrix 0 on samples not known to be 4:4:4 color.
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
  public val colorKnown: Boolean
    get() = fromNclx || codestream != null
}

private const val UNSPECIFIED_CODE_POINT = 2
