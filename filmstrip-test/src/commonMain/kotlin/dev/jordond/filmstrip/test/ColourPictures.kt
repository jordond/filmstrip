package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * Coding-independent code points, the four numbers of ITU-T H.273 a PNG `cICP` chunk or a HEIF `colr nclx` box
 * describes a picture's colour with.
 *
 * @property primaries The colour primaries code.
 * @property transfer The transfer characteristics code.
 * @property matrix The matrix coefficients code. A PNG only allows 0, RGB as stored.
 * @property fullRange Whether the samples use the full code range rather than the narrow video range.
 */
@InternalFilmstripApi
public class CicpTag(
  public val primaries: Int,
  public val transfer: Int,
  public val matrix: Int,
  public val fullRange: Boolean,
) {
  override fun toString(): String = "$primaries/$transfer/$matrix ${if (fullRange) "full" else "limited"}"
}

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
 * The colour chunks other than `iCCP` that [OverlayPixels.pngOf] writes ahead of the image data.
 *
 * Each one left null is not written.
 *
 * @property cicp The code points of a `cICP` chunk.
 * @property gamma A `gAMA` chunk's value, the encoding gamma times 100000, so 45455 for 1/2.2.
 * @property chromaticities The white point and primaries of a `cHRM` chunk.
 * @property srgbIntent The rendering intent of an `sRGB` chunk, from 0 to 3.
 */
@InternalFilmstripApi
public class PngColour(
  public val cicp: CicpTag? = null,
  public val gamma: Int? = null,
  public val chromaticities: Chromaticities? = null,
  public val srgbIntent: Int? = null,
)

/**
 * An eight-bit overlay picture whose colour is described by PNG chunks, as the chunks and the one texel it is painted
 * with.
 *
 * It carries no expected value.
 *
 * @property name What the description is, for test messages.
 * @property colour The `cICP`, `sRGB`, `gAMA` and `cHRM` chunks the picture carries.
 * @property profile The ICC profile of an `iCCP` chunk, or null for none.
 * @property texel The packed ARGB colour the picture is painted with, alpha included.
 */
@InternalFilmstripApi
public class PngPicture internal constructor(
  public val name: String,
  public val colour: PngColour,
  public val profile: ByteArray?,
  public val texel: Int,
) {
  /**
   * A PNG of [size] painted [texel] all over and carrying [colour] and [profile], as [OverlayPixels.pngOf] writes it.
   */
  public fun pngOf(size: Size): ByteArray = OverlayPixels.pngOf(size, texel, profile, colour)

  override fun toString(): String = name
}

/**
 * A sixteen-bit overlay picture tagged with an HDR transfer by a `cICP` chunk, as the code points and the texel it is
 * painted with.
 *
 * It carries no expected value.
 *
 * @property name What the description is, for test messages.
 * @property cicp The code points of the picture's `cICP` chunk.
 * @property texel The red, green, blue and alpha samples the picture is painted with, each from 0 to 65535.
 */
@InternalFilmstripApi
public class HdrPngPicture internal constructor(
  public val name: String,
  public val cicp: CicpTag,
  public val texel: List<Int>,
) {
  /**
   * A sixteen-bit RGBA PNG of [size] painted [texel] all over, as [OverlayPixels.sixteenBitPngOf] writes it.
   */
  public fun pngOf(size: Size): ByteArray = OverlayPixels.sixteenBitPngOf(size, texel, PngColour(cicp = cicp))

  override fun toString(): String = name
}

/**
 * Overlay pictures whose colour is described some way other than an ICC profile alone, one per description a backend
 * has to read the same way.
 *
 * The PNGs cover `cICP`, `gAMA` with and without `cHRM`, `cHRM` alone, an `sRGB` chunk and pictures carrying several
 * descriptions at once, one for each step of the order `cICP`, `iCCP`, `sRGB`, then `cHRM` and `gAMA`. The HDR PNGs
 * cover `cICP` PQ and HLG. The AVIF and HEIC stills cover `colr nclx` and `colr prof`, each alone, and the AVIF stills
 * also carry both at once. A few more HEICs set the range and colour their codestream signals against their `nclx`, and
 * one AVIF and one HEIC are painted with a blue difference near the top of its range. The codestream colour HEIC
 * carries no `nclx` and leaves its colour to its codestream, and [AvifColourPictures] holds the AVIFs that do the
 * same and the ones whose `nclx` names the identity matrix.
 * Every eight-bit PNG is painted [TEXEL], whose channels sit inside the mid-range and far
 * enough apart that a change of primaries or curve moves it.
 */
@InternalFilmstripApi
public object ColourPictures {
  private val HDR_TEXEL = listOf(0xA000, 0x8000, 0x6000, 0xFFFF)
  internal val FLAT_YCBCR = listOf(128, 96, 176)
  private val EDGE_YCBCR = listOf(26, 250, 120)

  /**
   * The colour every eight-bit PNG here is painted with.
   */
  public const val TEXEL: Int = 0xFFA06048.toInt()

  /**
   * `cICP` Display P3, primaries 12 with the sRGB transfer 13.
   */
  public val CICP_DISPLAY_P3: PngPicture = png("cICP Display P3", PngColour(cicp = CicpTag(12, 13, 0, true)))

  /**
   * `cICP` BT.709, holding primaries 1 and transfer 1.
   */
  public val CICP_BT709: PngPicture = png("cICP BT.709", PngColour(cicp = CicpTag(1, 1, 0, true)))

  /**
   * `cICP` linear light on BT.709 primaries, transfer 8.
   */
  public val CICP_LINEAR: PngPicture = png("cICP linear", PngColour(cicp = CicpTag(1, 8, 0, true)))

  /**
   * A `gAMA` of 100000 alone, linear light on the sRGB primaries.
   */
  public val GAMMA_LINEAR: PngPicture = png("gAMA linear", PngColour(gamma = LINEAR_GAMMA))

  /**
   * A `gAMA` of 45455 with Rec. 709 `cHRM`, which is the PNG description of sRGB.
   */
  public val GAMMA_SRGB: PngPicture =
    png("gAMA sRGB", PngColour(gamma = SRGB_GAMMA, chromaticities = Chromaticities.REC_709))

  /**
   * A `gAMA` of 45455 with Display P3 `cHRM`.
   */
  public val GAMMA_DISPLAY_P3: PngPicture =
    png("gAMA Display P3", PngColour(gamma = SRGB_GAMMA, chromaticities = Chromaticities.DISPLAY_P3))

  /**
   * Display P3 `cHRM` alone, with no `gAMA`.
   */
  public val CHROMATICITIES_DISPLAY_P3: PngPicture =
    png("cHRM Display P3", PngColour(chromaticities = Chromaticities.DISPLAY_P3))

  /**
   * An `sRGB` chunk alone, with the perceptual intent.
   */
  public val SRGB_CHUNK: PngPicture = png("sRGB chunk", PngColour(srgbIntent = 0))

  /**
   * A picture carrying `cICP` Display P3, an `iCCP` holding [IccPictures.SRGB]'s profile and a linear `gAMA`.
   *
   * Each of the three gives the texel a different colour, so a reader that picks the wrong one shows it.
   */
  public val PRECEDENCE: PngPicture =
    PngPicture(
      name = "cICP over iCCP over gAMA",
      colour = PngColour(cicp = CicpTag(12, 13, 0, true), gamma = LINEAR_GAMMA),
      profile = IccPictures.SRGB.profile,
      texel = TEXEL,
    )

  /**
   * A picture carrying an `iCCP` holding [IccPictures.DISPLAY_P3]'s profile and a linear `gAMA`.
   *
   * The two give the texel different colours, so a reader that lets `gAMA` win shows it.
   */
  public val ICCP_OVER_GAMMA: PngPicture =
    PngPicture(
      name = "iCCP over gAMA",
      colour = PngColour(gamma = LINEAR_GAMMA),
      profile = IccPictures.DISPLAY_P3.profile,
      texel = TEXEL,
    )

  /**
   * A picture carrying an `sRGB` chunk with the perceptual intent and a linear `gAMA`.
   *
   * The two give the texel different colours, so a reader that lets `gAMA` win shows it.
   */
  public val SRGB_OVER_GAMMA: PngPicture =
    png("sRGB over gAMA", PngColour(gamma = LINEAR_GAMMA, srgbIntent = 0))

  /**
   * Every eight-bit PNG.
   */
  public val PNGS: List<PngPicture> =
    listOf(
      CICP_DISPLAY_P3,
      CICP_BT709,
      CICP_LINEAR,
      GAMMA_LINEAR,
      GAMMA_SRGB,
      GAMMA_DISPLAY_P3,
      CHROMATICITIES_DISPLAY_P3,
      SRGB_CHUNK,
      PRECEDENCE,
      ICCP_OVER_GAMMA,
      SRGB_OVER_GAMMA,
    )

  /**
   * A sixteen-bit `cICP` PQ picture on BT.2020 primaries, 9 and 16.
   */
  public val CICP_PQ: HdrPngPicture = HdrPngPicture("cICP PQ", CicpTag(9, 16, 0, true), HDR_TEXEL)

  /**
   * A sixteen-bit `cICP` HLG picture on BT.2020 primaries, 9 and 18.
   */
  public val CICP_HLG: HdrPngPicture = HdrPngPicture("cICP HLG", CicpTag(9, 18, 0, true), HDR_TEXEL)

  /**
   * Every sixteen-bit HDR PNG.
   */
  public val HDR_PNGS: List<HdrPngPicture> = listOf(CICP_PQ, CICP_HLG)

  /**
   * `colr nclx` Display P3, 12, 13 and the BT.601 matrix 6 at full range.
   */
  public val NCLX_DISPLAY_P3: AvifPicture = avif("nclx Display P3", CicpTag(12, 13, 6, true))

  /**
   * `colr nclx` Display P3 beside a `colr prof` holding [IccPictures.LINEAR_SRGB]'s profile.
   */
  public val NCLX_DISPLAY_P3_WITH_PROFILE: AvifPicture =
    avif("nclx Display P3 with prof", CicpTag(12, 13, 6, true), IccPictures.LINEAR_SRGB.profile)

  /**
   * `colr nclx` BT.709 primaries and matrix 1 at limited range, with the sRGB transfer 13.
   *
   * Only its matrix and range set it apart from sRGB.
   */
  public val NCLX_BT709_LIMITED: AvifPicture = avif("nclx BT.709 limited", CicpTag(1, 13, 1, false))

  /**
   * `colr nclx` with unspecified primaries and transfer, 2 and 2, and matrix 6 at full range.
   */
  public val NCLX_UNSPECIFIED: AvifPicture = avif("nclx unspecified", CicpTag(2, 2, 6, true))

  /**
   * An AVIF whose only colour box is a `colr prof` holding [IccPictures.DISPLAY_P3]'s profile.
   */
  public val PROFILE_DISPLAY_P3: AvifPicture =
    AvifPicture("prof Display P3", nclx = null, IccPictures.DISPLAY_P3.profile, FLAT_YCBCR)

  /**
   * Every AVIF still.
   */
  public val AVIFS: List<AvifPicture> =
    listOf(NCLX_DISPLAY_P3, NCLX_DISPLAY_P3_WITH_PROFILE, NCLX_BT709_LIMITED, NCLX_UNSPECIFIED, PROFILE_DISPLAY_P3)

  /**
   * A HEIC with `colr nclx` BT.709 primaries and matrix 1 at full range, with the sRGB transfer 13.
   *
   * Like every HEIC in [HEICS], its codestream signals no range or colour of its own.
   */
  public val HEIC_NCLX_BT709_FULL: HeicPicture = heic("HEIC nclx BT.709 full", CicpTag(1, 13, 1, true))

  /**
   * A HEIC with `colr nclx` BT.709 primaries, the sRGB transfer 13 and the BT.601 matrix 6 at full range.
   *
   * Only its matrix sets it apart from [HEIC_NCLX_BT709_FULL].
   */
  public val HEIC_NCLX_BT601_FULL: HeicPicture = heic("HEIC nclx BT.601 full", CicpTag(1, 13, 6, true))

  /**
   * A HEIC with `colr nclx` BT.709 primaries and matrix 1 at limited range, with the sRGB transfer 13.
   *
   * Only its range sets it apart from [HEIC_NCLX_BT709_FULL].
   */
  public val HEIC_NCLX_BT709_LIMITED: HeicPicture = heic("HEIC nclx BT.709 limited", CicpTag(1, 13, 1, false))

  /**
   * A HEIC whose only colour box is a `colr prof` holding [IccPictures.DISPLAY_P3]'s profile.
   */
  public val HEIC_PROFILE_DISPLAY_P3: HeicPicture =
    HeicPicture("HEIC prof Display P3", nclx = null, IccPictures.DISPLAY_P3.profile, FLAT_YCBCR)

  /**
   * Every HEIC still whose codestream signals no range or colour of its own.
   */
  public val HEICS: List<HeicPicture> =
    listOf(HEIC_NCLX_BT709_FULL, HEIC_NCLX_BT601_FULL, HEIC_NCLX_BT709_LIMITED, HEIC_PROFILE_DISPLAY_P3)

  /**
   * A HEIC with `colr nclx` BT.709 primaries and matrix 1 at limited range, whose codestream sets the video full range
   * flag.
   *
   * Only its codestream's range sets it apart from [HEIC_NCLX_BT709_LIMITED].
   */
  public val HEIC_NCLX_LIMITED_VUI_FULL: HeicPicture =
    heic("HEIC nclx BT.709 limited, VUI full range", CicpTag(1, 13, 1, false), HevcVui.FullRange)

  /**
   * A HEIC with `colr nclx` BT.709 primaries and the BT.601 matrix 6 at full range, whose codestream describes its
   * colour with the BT.709 matrix 1 at full range.
   *
   * Only its codestream's range and colour description set it apart from [HEIC_NCLX_BT601_FULL].
   */
  public val HEIC_NCLX_BT601_VUI_BT709: HeicPicture =
    heic("HEIC nclx BT.601 full, VUI BT.709", CicpTag(1, 13, 6, true), HevcVui.FullRangeBt709)

  /**
   * A HEIC with no colour box and a codestream that signals no range or colour either.
   */
  public val HEIC_UNTAGGED: HeicPicture = HeicPicture("HEIC untagged", nclx = null, profile = null, FLAT_YCBCR)

  /**
   * A HEIC with `colr nclx` BT.709 primaries and matrix 1 at full range, with the sRGB transfer 13, whose codestream
   * states the same matrix and range beside Display P3 primaries 12.
   *
   * Only its codestream's primaries set it apart from [HEIC_NCLX_BT709_FULL].
   */
  public val HEIC_NCLX_BT709_VUI_DISPLAY_P3: HeicPicture =
    heic("HEIC nclx BT.709 full, VUI Display P3", CicpTag(1, 13, 1, true), HevcVui.FullRangeDisplayP3)

  /**
   * The HEICs whose codestream states a range, matrix or primaries their `nclx` does not record, and the HEIC with no
   * colour box and a silent codestream, for measuring which a decoder reads.
   *
   * The `nclx` and codestream of one disagree on range, of another on matrix and of another on primaries, so where a
   * decoder lands each shows which of the two it read.
   */
  public val HEIC_SIGNAL_VARIANTS: List<HeicPicture> =
    listOf(HEIC_NCLX_LIMITED_VUI_FULL, HEIC_NCLX_BT601_VUI_BT709, HEIC_UNTAGGED, HEIC_NCLX_BT709_VUI_DISPLAY_P3)

  /**
   * Every HEIC in [HEICS] followed by every one in [HEIC_SIGNAL_VARIANTS], the stills a backend's HEIC colour tests
   * read.
   */
  public val HEICS_WITH_SIGNAL_VARIANTS: List<HeicPicture> = HEICS + HEIC_SIGNAL_VARIANTS

  /**
   * An AVIF painted luma 26, blue difference 250 and red difference 120, with `colr nclx` BT.709 primaries and matrix 1
   * at full range and the sRGB transfer 13.
   *
   * Its blue difference sits six codes under the top of its range, so how wide a decoder takes full range chroma to be
   * moves its blue by most of a code.
   */
  public val NCLX_BT709_FULL_EDGE_CHROMA: AvifPicture =
    AvifPicture(
      "nclx BT.709 full, edge chroma",
      CicpTag(1, 13, 1, true),
      profile = null,
      EDGE_YCBCR,
      EDGE_FULL_CODESTREAM,
      EDGE_LIMITED_CODESTREAM,
    )

  /**
   * A HEIC painted like [NCLX_BT709_FULL_EDGE_CHROMA], with the same `colr nclx`, whose codestream signals no range or
   * colour of its own.
   */
  public val HEIC_NCLX_BT709_FULL_EDGE_CHROMA: HeicPicture =
    HeicPicture(
      "HEIC nclx BT.709 full, edge chroma",
      CicpTag(1, 13, 1, true),
      profile = null,
      EDGE_YCBCR,
      slice = EDGE_SLICE,
    )

  /**
   * A HEIC with no colour box whose codestream describes BT.709 primaries, the sRGB transfer 13 and the BT.709 matrix 1
   * at limited range.
   */
  public val HEIC_CODESTREAM_BT709_LIMITED: HeicPicture =
    HeicPicture("HEIC no nclx, VUI BT.709 limited", nclx = null, profile = null, FLAT_YCBCR, HevcVui.LimitedRangeBt709)

  /**
   * The HEICs whose codestream alone says how they land.
   */
  public val CODESTREAM_COLOUR_HEICS: List<HeicPicture> = listOf(HEIC_CODESTREAM_BT709_LIMITED)

  private fun png(
    name: String,
    colour: PngColour,
  ): PngPicture = PngPicture(name, colour, profile = null, texel = TEXEL)

  private fun avif(
    name: String,
    nclx: CicpTag,
    profile: ByteArray? = null,
  ): AvifPicture = AvifPicture(name, nclx, profile, FLAT_YCBCR)

  private fun heic(
    name: String,
    nclx: CicpTag,
    vui: HevcVui = HevcVui.Unsignalled,
  ): HeicPicture = HeicPicture(name, nclx, profile = null, FLAT_YCBCR, vui)

  // The AV1 codestreams come from ffmpeg 6.1.1 with libaom, on Ubuntu 24.04:
  //   ffmpeg -f lavfi -i "color=c=black:s=8x8,format=yuv420p,geq=lum=26:cb=250:cr=120" -frames:v 1 \
  //     -color_range pc -c:v libaom-av1 -aom-params lossless=1 -still-picture 1 -f avif edge.avif
  // with -color_range tv for the limited one, each the mdat payload of its file, whose av1C payload matches every other
  // AVIF still's. The HEVC slice comes from x265 4.3 and ffmpeg 9.0.2 on macOS:
  //   python3 -c "open('edge.yuv','wb').write(bytes([26])*4096+bytes([250])*1024+bytes([120])*1024)"
  //   x265 --input edge.yuv --input-res 64x64 --fps 1 --frames 1 --profile mainstillpicture --qp 4 --no-info \
  //     -o edge.hevc
  // and is the one coded slice of edge.hevc behind its length. Its parameter sets match every other HEIC still's, with
  // and without --range full and a colour description. Decoding any of them back with ffmpeg gives 26, 250 and 120 at
  // every sample.
  private const val EDGE_FULL_CODESTREAM = "0a051808bf6c4232111000000007c824994110cc07ba9dadd5c0"
  private const val EDGE_LIMITED_CODESTREAM = "0a051808bf6c0232111000000007c824994110cc07ba9dadd5c0"
  private const val EDGE_SLICE = "0000001a" + "2801af06786166d57fc43bffdeff9354ffffb45380ef734f6fc0"

  private const val SRGB_GAMMA = 45455
  private const val LINEAR_GAMMA = 100000
}
