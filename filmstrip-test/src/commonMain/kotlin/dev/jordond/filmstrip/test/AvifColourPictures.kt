package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.pictureYcbcrCoding

/**
 * AVIF stills whose landing turns on their codestream or on the identity matrix, painted like the AVIFs in
 * [ColourPictures].
 *
 * The codestream colour stills carry no `nclx` and leave their colour to their codestream. The identity stills are
 * 4:4:4 and their `nclx` names the identity matrix, so their luma, blue difference and red difference hold green, blue
 * and red.
 */
@InternalFilmstripApi
public object AvifColourPictures {
  /**
   * An AVIF with no colour box whose codestream signals limited range and no colour description.
   */
  public val AVIF_CODESTREAM_LIMITED: AvifPicture =
    AvifPicture(
      "AVIF no nclx, codestream limited",
      nclx = null,
      profile = null,
      ColourPictures.FLAT_YCBCR,
      ownCodestream = AvifPicture.LIMITED_CODESTREAM,
    )

  /**
   * An AVIF with no colour box whose codestream describes BT.709 primaries, the sRGB transfer 13 and the BT.709 matrix
   * 1 at limited range.
   */
  public val AVIF_CODESTREAM_BT709_LIMITED: AvifPicture =
    AvifPicture(
      "AVIF no nclx, codestream BT.709 limited",
      nclx = null,
      profile = null,
      ColourPictures.FLAT_YCBCR,
      ownCodestream = AvifPicture.BT709_LIMITED_CODESTREAM,
    )

  /**
   * An AVIF with no colour box whose codestream describes BT.2020 primaries 9, the PQ transfer 16 and the BT.2020
   * matrix 9 at limited range, painted like [AVIF_CODESTREAM_BT709_LIMITED].
   */
  public val AVIF_CODESTREAM_PQ_LIMITED: AvifPicture =
    AvifPicture(
      "AVIF no nclx, codestream PQ limited",
      nclx = null,
      profile = null,
      ColourPictures.FLAT_YCBCR,
      ownCodestream = PQ_LIMITED_CODESTREAM,
    )

  /**
   * A 4:4:4 AVIF with `colr nclx` BT.709 primaries, the sRGB transfer 13 and the identity matrix 0 at full range, whose
   * codestream signals full range and no colour description.
   *
   * Its luma, blue difference and red difference hold green, blue and red.
   */
  public val NCLX_IDENTITY_444: AvifPicture =
    AvifPicture(
      "nclx identity 4:4:4",
      CicpTag(1, 13, 0, true),
      profile = null,
      ColourPictures.FLAT_YCBCR,
      ownCodestream = AvifPicture.IDENTITY_444_CODESTREAM,
      configuration = AvifPicture.IDENTITY_444_CONFIGURATION,
    )

  /**
   * A 4:4:4 AVIF painted like [NCLX_IDENTITY_444], with the same codestream, whose `colr nclx` names unspecified
   * primaries and transfer 2 with the identity matrix 0 at full range.
   */
  public val NCLX_UNSPECIFIED_IDENTITY_444: AvifPicture =
    AvifPicture(
      "nclx unspecified identity 4:4:4",
      CicpTag(2, 2, 0, true),
      profile = null,
      ColourPictures.FLAT_YCBCR,
      ownCodestream = AvifPicture.IDENTITY_444_CODESTREAM,
      configuration = AvifPicture.IDENTITY_444_CONFIGURATION,
    )

  /**
   * The 4:4:4 AVIFs whose `nclx` names the identity matrix, on BT.709 primaries with the sRGB transfer and on
   * unspecified ones.
   */
  public val NCLX_IDENTITY_AVIFS: List<AvifPicture> = listOf(NCLX_IDENTITY_444, NCLX_UNSPECIFIED_IDENTITY_444)

  /**
   * The AVIFs whose codestream alone says how they land, and the identity AVIF whose codestream disagrees with its
   * `nclx`.
   */
  public val CODESTREAM_COLOUR_AVIFS: List<AvifPicture> =
    listOf(AVIF_CODESTREAM_LIMITED, AVIF_CODESTREAM_BT709_LIMITED, NCLX_IDENTITY_444)

  /**
   * The twelve-bit luma, blue difference and red difference codes every sample of a [twelveBit] AVIF decodes to.
   */
  public val TWELVE_BIT_YCBCR: List<Int> = listOf(2104, 1472, 2932)

  /**
   * An AVIF stored at twelve bits a sample with [nclx] as its colour box, painted [TWELVE_BIT_YCBCR].
   *
   * Its codestream is AV1 Professional profile 4:2:0, states no colour, and its range bit matches [nclx].
   */
  public fun twelveBit(nclx: CicpTag): AvifPicture =
    AvifPicture(
      "nclx $nclx, twelve-bit",
      nclx,
      profile = null,
      TWELVE_BIT_YCBCR,
      fullCodestream = TWELVE_BIT_FULL_CODESTREAM,
      limitedCodestream = TWELVE_BIT_LIMITED_CODESTREAM,
      configuration = TWELVE_BIT_CONFIGURATION,
      bitDepth = TWELVE_BITS,
    )

  // The twelve-bit codestreams come from ffmpeg 6.1.1 with libaom, on Ubuntu 24.04:
  //   ffmpeg -f lavfi -i "color=c=black:s=8x8,format=yuv420p12le,geq=lum=2104:cb=1472:cr=2932" -frames:v 1 \
  //     -color_range pc -c:v libaom-av1 -aom-params lossless=1 -still-picture 1 -f avif twelve.avif
  // with -color_range tv for the limited one. Each is the mdat payload of its file and the configuration is its av1C
  // payload, Professional profile 4:2:0 at twelve bits. Decoding either back with ffmpeg gives 2104, 1472 and 2932 at
  // every sample, and the two differ only in the sequence header's colour range bit.
  private const val TWELVE_BIT_FULL_CODESTREAM = "0a065808bf6f384032131000000007c752bf0470a7e834c0d1d0bbff30"
  private const val TWELVE_BIT_LIMITED_CODESTREAM = "0a065808bf6f184032131000000007c752bf0470a7e834c0d1d0bbff30"
  private const val TWELVE_BIT_CONFIGURATION = "81406c00"
  private const val TWELVE_BITS = 12

  // AvifPicture.BT709_LIMITED_CODESTREAM with its sequence header's colour primaries, transfer and matrix set to 9, 16
  // and 9. Each is a fixed eight-bit field of the colour description, so every other bit of the header and the frame
  // after it is left as it was and the samples decode to the same codes.
  private const val PQ_LIMITED_CODESTREAM = "0a081808bfec84880482320c10000002dd90816283ea9354"
}

/**
 * The 8-bit red, green and blue every sample of an AVIF or HEIC painted [ycbcr] lands on, through [ycbcrToRgb] with
 * the matrix, primaries, transfer and range core reads for [bytes].
 *
 * @throws IllegalStateException when core reads no matrix and range for [bytes].
 */
@InternalFilmstripApi
public fun landedRgb(
  bytes: ByteArray,
  ycbcr: List<Int>,
): IntArray {
  val coding = checkNotNull(pictureYcbcrCoding(bytes)) { "core reads no YCbCr coding for this picture" }
  return ycbcrToRgb(ycbcr[0], ycbcr[1], ycbcr[2], coding.matrix, coding.primaries, coding.transfer, coding.fullRange)
}
