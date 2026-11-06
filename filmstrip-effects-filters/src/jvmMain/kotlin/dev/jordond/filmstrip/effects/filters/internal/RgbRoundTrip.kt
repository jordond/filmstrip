package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.effect.FilterNode

/**
 * The conversions a look runs through to draw on red, green and blue, with the matrix and range named each way.
 *
 * A fragment opens on [opening], so whatever a clip's chain hands it reaches [toRgb] as 8 bit limited BT.709 4:2:0.
 * Going to RGB holds each chroma sample on the pixels it covers, and [toCodes] comes back to the same 4:2:0 with any
 * alpha kept. Each way rounds, which is ffmpeg's limit, and 4:2:0 cannot hold a stripe of colour narrower than its
 * chroma samples.
 */
internal object RgbRoundTrip {
  /**
   * Converts any frame to the limited range BT.709 4:2:0 codes the look's expressions are written in.
   */
  fun opening(): List<FilterNode> =
    listOf(
      FilterNode("scale", "out_color_matrix" to MATRIX, "out_range" to "tv"),
      FilterNode("format", "pix_fmts" to FRAME_FORMATS),
    )

  /**
   * Converts an [opening] frame to full range `gbrap`.
   */
  fun toRgb(): List<FilterNode> =
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
   * Converts a [toRgb] frame back to limited range `yuva420p`.
   */
  fun toCodes(): List<FilterNode> =
    listOf(
      FilterNode(
        "scale",
        "out_color_matrix" to MATRIX,
        "in_range" to "pc",
        "out_range" to "tv",
        "flags" to TO_CODES_FLAGS,
      ),
      FilterNode("format", "pix_fmts" to CODES_FORMAT),
    )
}

private const val FRAME_FORMATS = "yuv420p|yuva420p"
private const val RGB_FORMAT = "gbrap"
private const val CODES_FORMAT = "yuva420p"
private const val MATRIX = "bt709"

// Widening chroma by neighbour holds each sample on the pixels it covers, so the way back lands on the codes it read.
private const val TO_RGB_FLAGS = "neighbor+accurate_rnd+full_chroma_int"
private const val TO_CODES_FLAGS = "bilinear+accurate_rnd+full_chroma_int"
