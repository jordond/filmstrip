package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.FilterNode

/**
 * The conversions an SDR fragment runs through to draw on red, green and blue, with the matrix and range named each
 * way.
 *
 * A fragment opens on [opening], so whatever a clip's chain hands it reaches [toRgb] as 8 bit limited BT.709 4:2:0.
 * Going to RGB holds each chroma sample on the pixels it covers, and [toCodes] comes back to the same 4:2:0 with any
 * alpha kept. Each way rounds, which is ffmpeg's limit, and 4:2:0 cannot hold a stripe of colour narrower than its
 * chroma samples.
 */
@InternalFilmstripApi
public object RgbRoundTrip {
  /**
   * Converts any frame to the limited range BT.709 4:2:0 codes a fragment's expressions are written in.
   */
  public fun opening(): List<FilterNode> =
    listOf(
      FilterNode("scale", "out_color_matrix" to MATRIX, "out_range" to "tv", "flags" to OPENING_FLAGS),
      FilterNode("format", "pix_fmts" to FRAME_FORMATS),
    )

  /**
   * Converts an [opening] frame to full range `gbrap`.
   */
  public fun toRgb(): List<FilterNode> =
    listOf(
      FilterNode(
        "scale",
        "in_color_matrix" to MATRIX,
        "in_range" to "tv",
        "out_range" to "pc",
        "flags" to TO_RGB_FLAGS,
      ),
      FilterNode("format", "pix_fmts" to RGB_FORMAT),
    )

  /**
   * Converts a [toRgb] frame back to limited range `yuva420p`, or `yuva444p` with [fullChroma] for a frame that goes
   * back to RGB before its chroma is halved.
   */
  public fun toCodes(fullChroma: Boolean = false): List<FilterNode> =
    listOf(
      FilterNode(
        "scale",
        "out_color_matrix" to MATRIX,
        "in_range" to "pc",
        "out_range" to "tv",
        "flags" to TO_CODES_FLAGS,
      ),
      FilterNode("format", "pix_fmts" to if (fullChroma) FULL_CHROMA_FORMAT else CODES_FORMAT),
    )

  /**
   * The expression that reads back the code a `gbrap` sample held from [sample], the same sample after swscale widened
   * it to `gbrap16le`.
   *
   * swscale widens a code to about 256 times itself, a few either side, so a widened frame is not at 65535 full scale
   * and [toEightBits] would read it a code low from 129 up. Rounding a sixteen bit sample over 256 lands every code.
   */
  public fun codeOfWidened(sample: String): String = "round($sample/$WIDENED_PER_CODE)"

  /**
   * The expression that puts the code [codeOfWidened] reads from [sample] back at 65535 full scale, where [toEightBits]
   * lands it on that same code.
   *
   * A plane a fragment hands on untouched, like its alpha, goes through this rather than as the widened sample.
   */
  public fun keptOfWidened(sample: String): String = "${codeOfWidened(sample)}*$FULL_SCALE_PER_CODE"

  /**
   * The expression that carries [sample], a widened sample or a mix of them, to 65535 full scale without rounding it to
   * a code.
   *
   * A plane a fragment has read between pixels can sit between two codes, so it goes through this rather than
   * [keptOfWidened], and keeps the share it was read at.
   */
  public fun scaledOfWidened(sample: String): String = "$sample*$WIDENED_TO_FULL_SCALE"

  /**
   * Converts a sixteen bit `gbrap16le` frame to `gbrap`, every plane landing on its nearest eight bit code.
   *
   * swscale's step down from sixteen bits reads a code of eight as 256 wide and moves where it splits them by the
   * pixel, so each plane is rounded to its code first and handed over on the start of that code, which every split
   * reads the same. `lutrgb` caps a sixteen bit sample at 65280, which is ffmpeg's limit, so this step cannot hand a
   * frame on at 65535 full scale.
   */
  public fun toEightBits(): List<FilterNode> =
    listOf(
      FilterNode("lutrgb", "r" to TO_CODE_START, "g" to TO_CODE_START, "b" to TO_CODE_START, "a" to TO_CODE_START),
      FilterNode("format", "pix_fmts" to RGB_FORMAT),
    )

  /**
   * Moves every plane of a sixteen bit frame to the start of its nearest code at 256 a code, which swscale reads back
   * as that code on its way to eight bits.
   *
   * This is for a frame whose samples sit at 256 a code, like one widened from `gbrap` or the product of a `blend` on
   * widened frames, where [toEightBits] would read it at 65535 full scale.
   */
  public fun toCodeStarts(): FilterNode =
    FilterNode(
      "lutrgb",
      "r" to WIDENED_CODE_START,
      "g" to WIDENED_CODE_START,
      "b" to WIDENED_CODE_START,
      "a" to WIDENED_CODE_START,
    )
}

private const val FRAME_FORMATS = "yuv420p|yuva420p"
private const val RGB_FORMAT = "gbrap"
private const val CODES_FORMAT = "yuva420p"
private const val FULL_CHROMA_FORMAT = "yuva444p"
private const val MATRIX = "bt709"
private const val TO_CODE_START = "round(val/257)*256"
private const val WIDENED_PER_CODE = 256
private const val WIDENED_CODE_START = "round(val/$WIDENED_PER_CODE)*$WIDENED_PER_CODE"
private const val FULL_SCALE_PER_CODE = 257

// 257 over 256 is exact in a double, so one multiply lands where multiplying by 257 and dividing by 256 does.
private val WIDENED_TO_FULL_SCALE = FULL_SCALE_PER_CODE.toDouble() / WIDENED_PER_CODE

// A frame on another matrix or range is carried over with exact rounding, which keeps a grey code where it was.
private const val OPENING_FLAGS = "accurate_rnd+full_chroma_int"

// Widening chroma by neighbour holds each sample on the pixels it covers, so the way back lands on the codes it read.
private const val TO_RGB_FLAGS = "neighbor+accurate_rnd+full_chroma_int"
private const val TO_CODES_FLAGS = "bilinear+accurate_rnd+full_chroma_int"
