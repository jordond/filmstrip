package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * Sixty-four-by-sixty-four HEICs painted one flat colour with subsampled chroma, at eight and ten bits in 4:2:0 and
 * 4:2:2, for stills whose `nclx` names a matrix a decoder may not read.
 *
 * Each codestream is coded losslessly, and its sequence parameter set carries no video signal type, so the `nclx`
 * alone says how it lands.
 */
@InternalFilmstripApi
public object HeicSubsampledPictures {
  private val SIZE = Size(64, 64)
  private val EIGHT_BIT_YCBCR = listOf(120, 60, 155)
  private val TEN_BIT_YCBCR = listOf(600, 300, 900)

  /**
   * One subsampled still, its depth, chroma layout and the luma, blue difference and red difference codes every
   * sample decodes to.
   *
   * @property name How a test names the still, such as "the ten-bit 4:2:2 HEIC".
   * @property bitDepth The depth of every sample.
   * @property ycbcr The codes every sample decodes to, away from both ends of the range.
   */
  @InternalFilmstripApi
  public class Still internal constructor(
    public val name: String,
    public val bitDepth: Int,
    public val ycbcr: List<Int>,
    private val configuration: String,
    private val codestream: String,
  ) {
    /**
     * The still as the bytes of a HEIC file with a `colr nclx` of [nclx] and a `pixi` at its depth, built by
     * [HeifStills.still].
     */
    public fun bytes(nclx: CicpTag): ByteArray =
      HeifStills.still(
        "heic",
        "hvc1",
        HeifStills.box("hvcC", configuration.hex()),
        codestream.hex(),
        SIZE,
        nclx,
        bitDepth = bitDepth,
      )

    override fun toString(): String = name
  }

  /**
   * The eight-bit 4:2:0 still, HEVC Main Still Picture.
   */
  public val EIGHT_BIT_420: Still =
    Still("the eight-bit 4:2:0 HEIC", 8, EIGHT_BIT_YCBCR, EIGHT_BIT_420_CONFIGURATION, EIGHT_BIT_420_CODESTREAM)

  /**
   * The eight-bit 4:2:2 still, HEVC format range extensions.
   */
  public val EIGHT_BIT_422: Still =
    Still("the eight-bit 4:2:2 HEIC", 8, EIGHT_BIT_YCBCR, EIGHT_BIT_422_CONFIGURATION, EIGHT_BIT_422_CODESTREAM)

  /**
   * The ten-bit 4:2:0 still, HEVC format range extensions.
   */
  public val TEN_BIT_420: Still =
    Still("the ten-bit 4:2:0 HEIC", 10, TEN_BIT_YCBCR, TEN_BIT_420_CONFIGURATION, TEN_BIT_420_CODESTREAM)

  /**
   * The ten-bit 4:2:2 still, HEVC Main 4:2:2 10 Intra.
   */
  public val TEN_BIT_422: Still =
    Still("the ten-bit 4:2:2 HEIC", 10, TEN_BIT_YCBCR, TEN_BIT_422_CONFIGURATION, TEN_BIT_422_CODESTREAM)

  /**
   * Every subsampled still, eight bits before ten and 4:2:0 before 4:2:2.
   */
  public val ALL: List<Still>
    get() = listOf(EIGHT_BIT_420, EIGHT_BIT_422, TEN_BIT_420, TEN_BIT_422)

  // Each codestream and configuration comes from x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw frame of luma 120,
  // blue difference 60 and red difference 155 at eight bits, or 600, 300 and 900 at ten bits little endian, at every
  // sample. The raw frame is written with C the samples in each chroma plane, 1024 for 4:2:0 and 2048 for 4:2:2:
  //   python3 -c "open('still.yuv','wb').write(bytes([120])*4096+bytes([60])*C+bytes([155])*C)"
  //   python3 -c "import struct; p=lambda v,n: struct.pack('<H',v)*n; \
  //     open('still.yuv','wb').write(p(600,4096)+p(300,C)+p(900,C))"
  // The four were coded with these x265 flags beside --input still.yuv --input-res 64x64 --fps 1 --frames 1
  // --lossless --no-info:
  //   eight-bit 4:2:0: --profile mainstillpicture
  //   eight-bit 4:2:2: --input-csp i422 --output-depth 8 --profile main422-10-intra
  //   ten-bit 4:2:0: --input-depth 10 --output-depth 10 --profile main10
  //   ten-bit 4:2:2: --input-csp i422 --input-depth 10 --output-depth 10 --profile main422-10-intra
  // and then ffmpeg -i still.hevc -c copy -tag:v hvc1 still.mp4. The configuration is the mp4's hvcC payload. The
  // codestream is the one coded slice of the hevc file behind its length. Decoding each back with ffmpeg gives its
  // codes at every sample.
  private const val EIGHT_BIT_420_CONFIGURATION =
    "010370000000900000000000fff000fcfdf8f800000f03" +
      "a00001001840010c01ffff0370000003009000000300000300ffba0240a1000100254201010370000003009000000300000300ffa020" +
      "810596ea4932b80400000fa000000fa020a2000100064401c1718912"
  private const val EIGHT_BIT_420_CODESTREAM =
    "00000053" +
      "2801af05b8100c0033bfff30bd555589dddddeaef15ffff2276bffffa0fcf0090ba3aaeabaaeabb6cdb36cdb36cdb36cb7cfff63dd00" +
      "3f9bf2fc3c69a69a6aaaaaaaaaaa9ced9b515552d87aafb443cb3004c0"
  private const val EIGHT_BIT_422_CONFIGURATION =
    "0104080000009d3800000000fff000fcfef8f800000f03" +
      "a00001001740010c01ffff0408000003009d380000030000ffba0240a1000100244201010408000003009d380000030000ffb0208105" +
      "96ea4932b80400000fa000000fa020a2000100064401c1718912"
  private const val EIGHT_BIT_422_CODESTREAM =
    "00000054" +
      "2801af05b8100c6cf8d6fffcc2f555562777777abbc57fffc89daffffe83f3c0242e8eabaaeabaaedb36cdb36cdb36cdb2df3ffd8f74" +
      "00fe6fcbf0f1a69a69aaaaaaaaaaaa73b6dc118af8ecdd4c3d43b7b53ac0"
  private const val TEN_BIT_420_CONFIGURATION =
    "0104080000009db800000000fff000fcfdfafa00000f03" +
      "a00001001740010c01ffff0408000003009db80000030000ff959809a1000100274201010408000003009db80000030000ffa0208104" +
      "d96566924cae0100000303e800000303e808a2000100064401c171a912"
  private const val TEN_BIT_420_CODESTREAM =
    "0000007c" +
      "2801af05b8100c001b2018959c6b07690816d8d6358d635a609826098260982609bde2bfffe44ed7ffffbeafdf285e6a39dbcfaf3ebc" +
      "faf3ebd12b44ad12b44ad12b44ad12b3ff9ffec7ba007ff3fffe7eff9ebfce5fcc5fcc5fcc5fcc5fcc7fcc7fcc7fcc7fcc7fcc7fcc7f" +
      "cc6e76cda8aaa96c3d57da21e5980260"
  private const val TEN_BIT_422_CONFIGURATION =
    "0104080000009d3800000000fff000fcfefafa00000f03" +
      "a00001001740010c01ffff0408000003009d380000030000ffba0240a1000100254201010408000003009d380000030000ffb0208104" +
      "d96ea4932b80400000fa00000300fa02a2000100064401c1718912"
  private const val TEN_BIT_422_CODESTREAM =
    "0000007d" +
      "2801af05b8100c6cf87480625671ac1da4205b6358d6358d69826098260982609826f78affff913b5ffffefabf7ca179a8e76f3ebcfa" +
      "f3ebcfaf44ad12b44ad12b44ad12b44acffe7ffb1ee801ffcffff9fbfe7aff397f317f317f317f317f31ff31ff31ff31ff31ff31ff31" +
      "ff31b9db6e08c57c766ea61ea1dbda9d60"
}
