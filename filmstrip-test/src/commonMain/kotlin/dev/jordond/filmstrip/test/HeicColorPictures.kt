package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * HEIC stills whose landing turns on their depth, painted with the same samples as the twelve-bit AVIF in
 * [AvifColorPictures].
 *
 * Each codestream is coded losslessly, and its sequence parameter set carries no video signal type, so the `nclx`
 * alone says how it lands.
 */
@InternalFilmstripApi
public object HeicColorPictures {
  /**
   * The twelve-bit luma, blue difference and red difference codes every sample of a [twelveBit] HEIC decodes to, the
   * same as [AvifColorPictures.TWELVE_BIT_YCBCR].
   */
  public val TWELVE_BIT_YCBCR: List<Int> = AvifColorPictures.TWELVE_BIT_YCBCR

  /**
   * The twelve-bit coded item, HEVC Main 12 4:2:0, its `hvcC` and its one slice, for a HEIF still to carry as its
   * primary item, a tile or a thumbnail.
   */
  public val TWELVE_BIT_ITEM: HeifThumbnail
    get() =
      HeifThumbnail("hvc1", HeifStills.box("hvcC", TWELVE_BIT_CONFIGURATION.hex()), TWELVE_BIT_CODESTREAM.hex(), SIZE)

  /**
   * A sixty-four-by-sixty-four HEIC stored at twelve bits a sample with [nclx] as its color box and a twelve-bit
   * `pixi`, painted [TWELVE_BIT_YCBCR], built by [HeifStills.still].
   */
  public fun twelveBit(nclx: CicpTag): ByteArray {
    val item = TWELVE_BIT_ITEM
    return HeifStills.still("heic", item.itemType, item.configuration, item.codestream, SIZE, nclx, bitDepth = 12)
  }

  private val SIZE = Size(64, 64)

  // The codestream and configuration come from x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw little-endian twelve-bit
  // 4:2:0 frame of luma 2104, blue difference 1472 and red difference 2932 at every sample:
  //   python3 -c "import struct; open('flat12.yuv','wb').write(struct.pack('<H',2104)*4096+\
  //     struct.pack('<H',1472)*1024+struct.pack('<H',2932)*1024)"
  //   x265 --input flat12.yuv --input-depth 12 --output-depth 12 --input-res 64x64 --fps 1 --frames 1 \
  //     --profile main12 --lossless --no-info -o flat12.hevc
  //   ffmpeg -i flat12.hevc -c copy -tag:v hvc1 flat12.mp4
  // The configuration is the mp4's hvcC payload. The codestream is the one coded slice of flat12.hevc behind its
  // length. Decoding it back with ffmpeg gives 2104, 1472 and 2932 at every sample.
  private const val TWELVE_BIT_CONFIGURATION =
    "01040800000099b800000000fff000fcfdfcfc00000f03" +
      "a00001001740010c01ffff04080000030099b80000030000ff959809" +
      "a10001002642010104080000030099b80000030000ffa02081045296566924cae01000003e8000003e8080" +
      "a2000100064401c171a912"
  private const val TWELVE_BIT_CODESTREAM =
    "00000084" +
      "2801af05b8100c001b20188f11757291607d7d7d7d83ababababababac3de2bfffe44ed7ffffe9a43f4cf274c3469570a3e028f80a3e" +
      "028f80a3f7e8fdfa3f7e8fdfa3f7e8fdfa3f7e8f939ffec7ba007ffadfffd6effd6affac5fea97faa5fea97faa5fea9ffaa7fea9ffaa7f" +
      "ea9ffaa7fea9ffaa6e76cda8aaa96c3d57da21e5980260"
}
