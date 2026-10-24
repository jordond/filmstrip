package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * Sixty-four-by-sixty-four 4:4:4 HEICs painted one flat colour, at eight, ten and twelve bits, for HEIC stills whose
 * `nclx` names the identity matrix.
 *
 * Each codestream is coded losslessly, and its sequence parameter set carries no video signal type, so the `nclx`
 * alone says how it lands. Under the identity matrix its luma, blue difference and red difference hold green, blue
 * and red.
 */
@InternalFilmstripApi
public object HeicIdentityPictures {
  /**
   * The eight-bit luma, blue difference and red difference codes every sample of [ITEM] decodes to, away from both
   * ends of the range so a missing range stretch shows.
   */
  public val YCBCR: List<Int> = listOf(120, 60, 155)

  /**
   * The eight-bit coded item, HEVC Main 4:4:4 Still Picture, its `hvcC` and its one slice, for a HEIF still to carry
   * as its primary item, a tile or a thumbnail.
   */
  public val ITEM: HeifThumbnail
    get() = HeifThumbnail("hvc1", HeifStills.box("hvcC", CONFIGURATION.hex()), CODESTREAM.hex(), SIZE)

  /**
   * The ten-bit luma, blue difference and red difference codes every sample of [TEN_BIT_ITEM] decodes to, away from
   * both ends of the range so a missing range stretch shows.
   */
  public val TEN_BIT_YCBCR: List<Int> = listOf(600, 300, 900)

  /**
   * The ten-bit coded item, HEVC Main 4:4:4 10 Intra, its `hvcC` and its one slice, for a HEIF still to carry as its
   * primary item, a tile or a thumbnail.
   */
  public val TEN_BIT_ITEM: HeifThumbnail
    get() = HeifThumbnail("hvc1", HeifStills.box("hvcC", TEN_BIT_CONFIGURATION.hex()), TEN_BIT_CODESTREAM.hex(), SIZE)

  /**
   * The twelve-bit coded item, HEVC Main 4:4:4 12 Intra, its `hvcC` and its one slice, for a HEIF still to carry as
   * its primary item, a tile or a thumbnail.
   */
  public val TWELVE_BIT_ITEM: HeifThumbnail
    get() =
      HeifThumbnail("hvc1", HeifStills.box("hvcC", TWELVE_BIT_CONFIGURATION.hex()), TWELVE_BIT_CODESTREAM.hex(), SIZE)

  /**
   * The eight-bit picture as the bytes of a HEIC file with a `colr nclx` of [nclx], built by [HeifStills.still].
   *
   * @param nclx The code points of the `colr nclx` box, normally naming the identity matrix 0.
   */
  public fun bytes(nclx: CicpTag): ByteArray =
    HeifStills.still("heic", ITEM.itemType, ITEM.configuration, ITEM.codestream, SIZE, nclx = nclx)

  /**
   * The ten-bit picture as the bytes of a HEIC file with a `colr nclx` of [nclx] and a ten-bit `pixi`, built by
   * [HeifStills.still].
   *
   * @param nclx The code points of the `colr nclx` box, normally naming the identity matrix 0.
   */
  public fun tenBitBytes(nclx: CicpTag): ByteArray {
    val item = TEN_BIT_ITEM
    return HeifStills.still("heic", item.itemType, item.configuration, item.codestream, SIZE, nclx, bitDepth = 10)
  }

  /**
   * The twelve-bit picture as the bytes of a HEIC file with a `colr nclx` of [nclx] and a twelve-bit `pixi`, built by
   * [HeifStills.still].
   *
   * @param nclx The code points of the `colr nclx` box, normally naming the identity matrix 0.
   */
  public fun twelveBitBytes(nclx: CicpTag): ByteArray {
    val item = TWELVE_BIT_ITEM
    return HeifStills.still("heic", item.itemType, item.configuration, item.codestream, SIZE, nclx, bitDepth = 12)
  }

  private val SIZE = Size(64, 64)

  // The codestream and configuration come from x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw 4:4:4 frame of luma 120,
  // blue difference 60 and red difference 155 at every sample:
  //   python3 -c "open('flat444.yuv','wb').write(bytes([120])*4096+bytes([60])*4096+bytes([155])*4096)"
  //   x265 --input flat444.yuv --input-csp i444 --input-res 64x64 --fps 1 --frames 1 \
  //     --profile main444-stillpicture --lossless --no-info -o flat444.hevc
  //   ffmpeg -i flat444.hevc -c copy -tag:v hvc1 flat444.mp4
  // The configuration is the mp4's hvcC payload. The codestream is the one coded slice of flat444.hevc behind its
  // length. Decoding it back with ffmpeg gives 120, 60 and 155 at every sample.
  private const val CONFIGURATION =
    "0104080000009e3800000000fff000fcfff8f800000f03" +
      "a00001001740010c01ffff0408000003009e380000030000ff959809" +
      "a1000100254201010408000003009e380000030000ff90041020b2cacd24995c02000007d0000007d010" +
      "a2000100084401c17030629120"
  private const val CODESTREAM =
    "00000054" +
      "2801af05b8100c00f0259fff7955555577777776543fffe44ed7ffff41f9e012174755d5755d576d9b66d9b66d9b66d96f9ffec7ba00" +
      "7f37e5f878d34d34d555555555555e04cbbb6d4d556b62aac6d28f8cd81f"

  // The ten-bit codestream and configuration come from the same x265 and ffmpeg, from a raw little-endian ten-bit
  // 4:4:4 frame of luma 600, blue difference 300 and red difference 900 at every sample:
  //   python3 -c "import struct; open('flat444p10.yuv','wb').write(struct.pack('<H',600)*4096+\
  //     struct.pack('<H',300)*4096+struct.pack('<H',900)*4096)"
  //   x265 --input flat444p10.yuv --input-csp i444 --input-depth 10 --output-depth 10 --input-res 64x64 --fps 1 \
  //     --frames 1 --profile main444-10-intra --lossless --no-info -o flat444p10.hevc
  //   ffmpeg -i flat444p10.hevc -c copy -tag:v hvc1 flat444p10.mp4
  // The configuration is the mp4's hvcC payload. The codestream is the one coded slice of flat444p10.hevc behind its
  // length. Decoding it back with ffmpeg gives 600, 300 and 900 at every sample.
  private const val TEN_BIT_CONFIGURATION =
    "0104080000009c3800000000fff000fcfffafa00000f03" +
      "a00001001740010c01ffff0408000003009c380000030000ffba0240" +
      "a1000100254201010408000003009c380000030000ff900410209b2dd49265700800001f4000001f4040" +
      "a2000100084401c17030609120"
  private const val TEN_BIT_CODESTREAM =
    "0000007e" +
      "2801af05b8100c00f015a00ff93ff25fc77e1bd6f5bd6f5bd7f5fd7f5fd7f5fd7f5ca87fffc89dafffff7d5fbe50bcd473b79f5e7d79" +
      "f5e7d7a256895a256895a256895a2567ff3ffd8f7400ffe7fffcfdff3d7f9cbf98bf98bf98bf98bf98ff98ff98ff98ff98ff98ff98ff" +
      "98ef0265ddb6a6aab5b155636947c66c0f80"

  // The twelve-bit codestream and configuration come from x265 4.3 and ffmpeg 9.0.2 the same way, from a raw
  // little-endian twelve-bit 4:4:4 frame of luma 2400, blue difference 1200 and red difference 3600 at every sample:
  //   python3 -c "import struct; open('flat444p12.yuv','wb').write(struct.pack('<H',2400)*4096+\
  //     struct.pack('<H',1200)*4096+struct.pack('<H',3600)*4096)"
  //   x265 --input flat444p12.yuv --input-csp i444 --input-depth 12 --output-depth 12 --input-res 64x64 --fps 1 \
  //     --frames 1 --profile main444-12-intra --lossless --no-info -o flat444p12.hevc
  //   ffmpeg -i flat444p12.hevc -c copy -tag:v hvc1 flat444p12.mp4
  // Decoding it back with ffmpeg gives 2400, 1200 and 3600 at every sample.
  private const val TWELVE_BIT_CONFIGURATION =
    "010408000000983800000000fff000fcfffcfc00000f03" +
      "a00001001740010c01ffff04080000030098380000030000ffba0240" +
      "a10001002642010104080000030098380000030000ff900410208a52dd49265700800001f400000301f404" +
      "a2000100084401c17030609120"
  private const val TWELVE_BIT_CODESTREAM =
    "00000099" +
      "2801af05b8100c00f015a00ffe5bffcb5ff2b7f93bf8fbf8fbf8fbf8fbf8fff8fff8fff8fff8fff8fff8fff8fca87fffc89dafffffdf" +
      "e67efed4efd58df4c17a385e8e17a385e8e17a67de99f7a67de99f7a67de99f7a67de9073ffd8f7400fffa0bfff415ffd037fe7fbfe7" +
      "bbfe7bbfe7bbfe7bbfe7bffe7bffe7bffe7bffe7bffe7bffe7bffe7bef0265ddb6a6aab5b155636947c66c0f80"
}
