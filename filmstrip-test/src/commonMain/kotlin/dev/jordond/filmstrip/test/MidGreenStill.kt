package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * A sixty-four-by-sixty-four HEIC painted [AuthoredColors.MID_GREEN], a picture only ffmpeg decodes since the JDK has
 * no HEIC reader.
 *
 * The codestream is eight-bit HEVC Main Still Picture 4:2:0, coded losslessly, so every sample decodes to [YCBCR]. Its
 * sequence parameter set signals the same full range BT.601 coefficients as the [NCLX] box, so a decoder that reads
 * either lands those codes on the same color.
 */
@InternalFilmstripApi
public object MidGreenStill {
  /**
   * The eight-bit luma, blue difference and red difference codes every sample decodes to.
   */
  public val YCBCR: List<Int> = listOf(93, 94, 96)

  /**
   * The still's `colr nclx`, primaries and transfer unspecified with BT.601 coefficients over the full range.
   */
  public val NCLX: CicpTag = CicpTag(primaries = 2, transfer = 2, matrix = 6, fullRange = true)

  /**
   * The HEIC file, built by [HeifStills.still].
   */
  public fun heic(): ByteArray =
    HeifStills.still(
      "heic",
      "hvc1",
      HeifStills.box("hvcC", CONFIGURATION.hex()),
      CODESTREAM.hex(),
      Size(64, 64),
      nclx = NCLX,
    )

  // The codestream and configuration come from x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw eight-bit 4:2:0 frame of
  // luma 93, blue difference 94 and red difference 96 at every sample:
  //   python3 -c "open('flat8.yuv','wb').write(bytes([93])*4096+bytes([94])*1024+bytes([96])*1024)"
  //   x265 --input flat8.yuv --input-depth 8 --output-depth 8 --input-res 64x64 --fps 1 --frames 1 \
  //     --profile mainstillpicture --lossless --no-info --range full --colormatrix smpte170m -o flat8.hevc
  //   ffmpeg -i flat8.hevc -c copy -tag:v hvc1 flat8.mp4
  // The configuration is the mp4's hvcC payload. The codestream is the one coded slice of flat8.hevc behind its length.
  // Decoding the mp4 back to RGB with ffmpeg gives 48, 127 and 33 at every sample.
  private const val CONFIGURATION =
    "010370000000900000000000fff000fcfdf8f800000f03" +
      "a00001001840010c01ffff0370000003009000000300000300ffba0240" +
      "a1000100294201010370000003009000000300000300ffa020810596ea4932b9b81010302000007d000003007d01" +
      "a2000100064401c1718912"
  private const val CODESTREAM =
    "00000055" +
      "2801af05b8100c0033bfffe5d2887065dbb76ede820408102040975e2bfffe44ed7fffe544786e267cf9f3e88f1e3c78f1e3" +
      "919ffec839ffff5feaf9bb5d75d75f7df7df7df7b9db36a2aaa5b0f55f688796600980"
}
