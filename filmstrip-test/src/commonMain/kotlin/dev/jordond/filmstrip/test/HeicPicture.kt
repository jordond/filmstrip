package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * A sixty-four-by-sixty-four HEIC still painted one flat color, as the color boxes it carries, the color its
 * codestream signals and the samples it decodes to.
 *
 * It carries no expected value. The codestream is HEVC Main Still Picture, 4:2:0 and eight-bit, and stores the same
 * luma and chroma at every sample, so its color depends on [nclx]'s matrix and range as well as its primaries and
 * transfer.
 *
 * @property name What the description is, for test messages.
 * @property nclx The code points of a `colr nclx` box, or null for none.
 * @property profile The ICC profile of a `colr prof` box, or null for none.
 * @property ycbcr The luma, blue difference and red difference codes every sample decodes to.
 * @property vui What the sequence parameter set's video usability information signals about range and color.
 * @property slice The coded slice behind its four byte length, as hex. It decodes to [ycbcr] under the parameter sets
 * of every [HevcVui].
 */
@InternalFilmstripApi
public class HeicPicture internal constructor(
  override val name: String,
  override val nclx: CicpTag?,
  override val profile: ByteArray?,
  override val ycbcr: List<Int>,
  public val vui: HevcVui = HevcVui.Unsignalled,
  internal val slice: String = CODESTREAM,
) : HeifPicture {
  /**
   * The HEVC codestream the picture's item holds, one coded slice behind a four byte length.
   */
  override fun codestream(): ByteArray = slice.hex()

  /**
   * The `hvcC` box's payload, carrying the video, sequence and picture parameter sets, its sequence parameter set
   * signalling what [vui] names.
   */
  internal fun configuration(): ByteArray = vui.configuration.hex()

  /**
   * The picture as the bytes of a HEIC file, built by [HeifStills.still].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the picture, or null for none.
   */
  override fun bytes(thumbnail: HeifThumbnail?): ByteArray =
    HeifStills.still(
      brand = "heic",
      itemType = "hvc1",
      configuration = HeifStills.box("hvcC", configuration()),
      codestream = codestream(),
      size = HEIC_SIZE,
      nclx = nclx,
      profile = profile,
      thumbnail = thumbnail,
    )

  /**
   * The picture as a two-by-two grid of its codestream, with [nclx] on the grid item, built by [HeifStills.grid].
   *
   * It is 128 pixels a side and leaves out [profile].
   *
   * @param thumbnail A thumbnail item the file carries ahead of the grid, or null for none.
   */
  override fun gridBytes(thumbnail: HeifThumbnail?): ByteArray =
    HeifStills.grid(
      brand = "heic",
      itemType = "hvc1",
      configuration = HeifStills.box("hvcC", configuration()),
      tile = codestream(),
      tileSize = HEIC_SIZE,
      nclx = nclx,
      thumbnail = thumbnail,
    )

  /**
   * The picture's coded item at sixty-four pixels a side, with the codestream signal [vui] names, for another HEIF
   * still to carry as its thumbnail.
   */
  override fun thumbnail(): HeifThumbnail =
    HeifThumbnail("hvc1", HeifStills.box("hvcC", configuration()), codestream(), HEIC_SIZE)

  override fun toString(): String = name

  internal companion object {
    val HEIC_SIZE = Size(64, 64)

    // The codestream and configurations come from x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw frame of luma 128,
    // blue difference 96 and red difference 176 at every sample:
    //   python3 -c "open('flat.yuv','wb').write(bytes([128])*4096+bytes([96])*1024+bytes([176])*1024)"
    //   x265 --input flat.yuv --input-res 64x64 --fps 1 --frames 1 --profile mainstillpicture --qp 4 --no-info \
    //     -o flat.hevc
    //   ffmpeg -i flat.hevc -c copy -tag:v hvc1 flat.mp4
    // adding --range full for FullRange, --range full --colorprim bt709 --transfer iec61966-2-1 --colormatrix bt709
    // for FullRangeBt709, and --colorprim bt709 --transfer iec61966-2-1 --colormatrix bt709 for LimitedRangeBt709.
    // Each configuration is its mp4's hvcC payload. The codestream is the one coded slice of flat.hevc behind its
    // length, the same in all four. Decoding any of them back with ffmpeg gives 128, 96 and 176 at every sample.
    const val CODESTREAM = "00000015" + "2801af067861867ffff023ff21f9476f01d8453dbf"
  }
}
