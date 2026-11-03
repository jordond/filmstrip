package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * Alpha planes for the HEIF stills [ColorPictures] holds, coded losslessly so each decodes to the codes [avifAlpha],
 * [heicAlpha] and [avifTileAlpha] give.
 *
 * The AVIF ones are eight pixels a side, the size of an [AvifPicture], and the HEIC one sixty-four, the size of a
 * [HeicPicture]. Their codes run from 40 to 234 and change from texel to texel, quadrant to quadrant or tile to tile.
 * A limited range plane stores the same codes as its full range one and differs only in the range its codestream
 * states.
 */
@InternalFilmstripApi
public object AlphaPlanes {
  /**
   * The eight-by-eight AV1 alpha plane, a monochrome codestream of [avifAlpha]'s codes.
   *
   * @param premultiplied Whether the file records the color item as multiplied by this alpha.
   * @param fullRange Whether the sequence header states full range rather than limited.
   */
  public fun avif(
    premultiplied: Boolean = false,
    fullRange: Boolean = true,
  ): HeifAlphaPlane =
    HeifAlphaPlane(
      itemType = "av01",
      configuration = HeifStills.box("av1C", AV1_CONFIGURATION.hex()),
      codestream = (if (fullRange) AV1_CODESTREAM else AV1_LIMITED_CODESTREAM).hex(),
      size = Size(8, 8),
      premultiplied = premultiplied,
    )

  /**
   * The sixty-four-by-sixty-four HEVC alpha plane, a 4:2:0 codestream whose luma holds [heicAlpha]'s codes.
   *
   * @param premultiplied Whether the file records the color item as multiplied by this alpha.
   * @param fullRange Whether the sequence parameter set states full range rather than limited.
   */
  public fun heic(
    premultiplied: Boolean = false,
    fullRange: Boolean = true,
  ): HeifAlphaPlane =
    HeifAlphaPlane(
      itemType = "hvc1",
      configuration = HeifStills.box("hvcC", (if (fullRange) HEVC_CONFIGURATION else HEVC_LIMITED_CONFIGURATION).hex()),
      codestream = HEVC_CODESTREAM.hex(),
      size = Size(64, 64),
      premultiplied = premultiplied,
    )

  /**
   * The eight-by-eight full range AV1 alpha plane of grid tile [index], 0 to 5, a monochrome codestream of
   * [avifTileAlpha]'s codes for that tile.
   */
  public fun avifTile(index: Int): HeifAlphaPlane =
    HeifAlphaPlane(
      itemType = "av01",
      configuration = HeifStills.box("av1C", AV1_CONFIGURATION.hex()),
      codestream = AV1_TILES[index].hex(),
      size = Size(8, 8),
    )

  /**
   * The eight-bit alpha code [avif] stores at column [x] and row [y], from 40 at the top left up to 194.
   */
  public fun avifAlpha(
    x: Int,
    y: Int,
  ): Int = 40 + 20 * x + 2 * y

  /**
   * The eight-bit alpha code [avifTile] stores for tile [index] at column [x] and row [y] of the tile, [avifAlpha]'s
   * code raised by eight for each tile ahead of it.
   */
  public fun avifTileAlpha(
    index: Int,
    x: Int,
    y: Int,
  ): Int = avifAlpha(x, y) + 8 * index

  /**
   * The eight-bit alpha code [heic] stores at column [x] and row [y], one of 72, 104, 152 and 184 by quadrant, left
   * to right and then top to bottom.
   */
  public fun heicAlpha(
    x: Int,
    y: Int,
  ): Int = HEIC_QUADRANTS[x / 32 + 2 * (y / 32)]

  // The codestreams come from libaom 3.15.1's aomenc and x265 4.3 on macOS. The AV1 ones are
  //   aomenc --limit=1 --lossless=1 --obu --monochrome -o alpha.obu alpha.y4m
  // of an 8x8 C420jpeg frame whose luma is avifAlpha, or avifTileAlpha for each tile, without its leading temporal
  // delimiter. The full range ones have XCOLORRANGE=FULL in the frame's header and the limited one leaves it out. Their
  // configuration is the av1C payload for Main profile with monochrome set. The HEVC ones are
  //   x265 --input alpha.yuv --input-res 64x64 --fps 1 --frames 1 --profile mainstillpicture --lossless \
  //     --range full --no-info -o alpha.hevc
  // of a 64x64 4:2:0 frame whose luma is heicAlpha and whose chroma is 128, with --range limited for the limited one.
  // Both give the same coded slice, kept behind its length, and each configuration is the hvcC payload of
  // `ffmpeg -i alpha.hevc -c copy -tag:v hvc1 alpha.mp4`. Decoding any of them back with ffmpeg 9.0.2 gives the luma
  // it was coded from at every sample.
  private const val AV1_CODESTREAM =
    "0a051808bfed50321e1000870a5395cf4b3b7b44d0a111a3dc6274ad69cb6cab6b197f2c8451ed"
  private const val AV1_LIMITED_CODESTREAM =
    "0a051808bfed10321e1000870a5395cf4b3b7b44d0a111a3dc6274ad69cb6cab6b197f2c8451ed"
  private val AV1_TILES =
    listOf(
      AV1_CODESTREAM,
      "0a051808bfed50321e1000870a5395cf4b3b7918b0a111a3dc6274ad69cb6cab6b197f2c8451ed",
      "0a051808bfed50321e1000870a5395cf4b3b76ec90a111a3dc6274ad69cb6cab6b197f2c8451ed",
      "0a051808bfed50321e1000870a5395cf4b3b8b41c284468f7189d2b5a72db2adac65fcb21147b4",
      "0a051808bfed50321e1000870a5395cf4b3b82914284468f7189d2b5a72db2adac65fcb21147b4",
      "0a051808bfed50321e1000870a5395cf4b3b9fa607faffd70b1bfb5c5463a28117cf024277e550",
    )
  private const val AV1_CONFIGURATION = "81001c00"
  private const val HEVC_CONFIGURATION =
    "010370000000900000000000fff000fcfdf8f800000f03a00001001840010c01ffff0370000003009000000300000300ffba0240a10001" +
      "00264201010370000003009000000300000300ffa020810596ea4932b9b02000007d000003007d01a2000100064401c1718912"
  private const val HEVC_LIMITED_CONFIGURATION =
    "010370000000900000000000fff000fcfdf8f800000f03a00001001840010c01ffff0370000003009000000300000300ffba0240a10001" +
      "00264201010370000003009000000300000300ffa020810596ea4932b9a02000007d000003007d01a2000100064401c1718912"
  private const val HEVC_CODESTREAM =
    "000001b22801af05b8100c7f819bffff788bab948b03ebebebec1d5d5d5d5d5d5d61f4cb51c8d6a035d555a44000364033bef775741df7" +
      "df7ecb2cb2cb2cb540000e24ebd6d0bf309da5257d6c04245000000302237c884e3c359aa17e8ab827180008860a356f43251f4792adf" +
      "5123eebb00000084eba40c39bda4c3f3e3f60ef800092428a130d4ff0027a8705c2a0a6000005fdc615f6b4eb78a76ca785aa0000c7a" +
      "001a461bcabe7b5123bc0e6ec0000030015166d6cb0701c45dcf73dcf73e24090240902409023f4e00130e118603d90692622a93146f" +
      "764c79a125c0000221fe3258b12e1f0662e207ef31d79e5867c0027fa6100121ca965507770d08575af81b19039000003006d98dd426" +
      "31924201d311bc0d4bfd5f9742000ab5ebbb6eebc41cbd08df38a9bca374e3d990df3a49600000301535587fd4d26fbefc4659659659" +
      "612001476489f5e39638e39371c71c71c71f80000095b8013b494d80edc7cdacd020001f606fc86957a29faab7c6db73a8800000300" +
      "0c89db4070828b241720175465400751b549b5cd22a8c5cea6126eb00000197295e3e3158e6a091a09843280104e564ff63dab30b571" +
      "a5d4cfb464"
  private val HEIC_QUADRANTS = intArrayOf(72, 104, 152, 184)
}
