package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.YcbcrEvaluator
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.equivalentCode
import dev.jordond.filmstrip.media.evaluator

/**
 * How [FfmpegPictureDecoder] decodes a picture, as [pictureRoute] picks it for the picture's YCbCr coding.
 */
internal sealed interface PictureRoute {
  // ffmpeg converts the frame to RGB through [filter] and hands it back as a PNG.
  class Converted(
    val filter: String,
  ) : PictureRoute

  // ffmpeg hands back the frame's 4:4:4 planes at [bitDepth] bits through [filter], and [evaluator] turns each
  // sample into RGB.
  class Planes(
    val filter: String,
    val bitDepth: Int,
    val evaluator: YcbcrEvaluator,
  ) : PictureRoute

  // The picture is not decoded, for [reason].
  class Refused(
    val reason: String,
  ) : PictureRoute
}

/**
 * The route a picture whose [coding] core reads is decoded through.
 *
 * A picture with no coding, or whose [YcbcrCoding.colourKnown] is false, is [PictureRoute.Converted] through a graph
 * that hands the frame on untouched, so ffmpeg's decoder converts it with what it reads itself. Every other picture
 * lands by its [YcbcrCoding.reading].
 *
 * - A coding with a [YcbcrCoding.refusal] is [PictureRoute.Refused] with it.
 * - A [YcbcrReading.KrKb] reading whose [equivalentCode] swscale names, 1, 4, 6, 7 and 9, is [PictureRoute.Converted]
 * through [ycbcrScale]. That covers 2 and 5, which read as 6, and 12 on primaries a named matrix matches.
 * - Every other reading, identity, YCgCo, constant luminance and 12 on primaries no named matrix matches, is
 * [PictureRoute.Planes]. The frame's planes come back at [YcbcrCoding.bitDepth] bits and core's evaluator for the
 * reading at that depth and [YcbcrCoding.fullRange] turns them into RGB. Without a [YcbcrCoding.bitDepth] it is
 * [PictureRoute.Refused] with [otherCodecDepthReason] for [CodecKind.Other] and [unknownDepthReason] otherwise.
 *
 * The identity planes are taken as they are through `mergeplanes`, since ffmpeg's HEVC decoder hands them back named
 * green, blue and red, which the merge takes in the same order. Any other frame is tagged BT.709 by `setparams`, since
 * ffmpeg 9 refuses a frame tagged YCgCo or constant luminance outright, then goes through `scale` to 4:4:4 samples at
 * its own depth and range, which converts no matrix between two YCbCr formats.
 */
internal fun pictureRoute(coding: YcbcrCoding?): PictureRoute {
  if (coding == null || !coding.colourKnown) return PictureRoute.Converted(PASS_THROUGH)
  coding.refusal?.let { return PictureRoute.Refused(it) }
  val reading = coding.reading
  if (swscaleMatrix(reading) != null) return PictureRoute.Converted(ycbcrScale(coding))

  val bitDepth =
    coding.bitDepth ?: return PictureRoute.Refused(
      if (coding.codec == CodecKind.Other) otherCodecDepthReason(coding.matrix) else unknownDepthReason(coding.matrix),
    )
  val filter =
    if (reading == YcbcrReading.Identity) {
      "mergeplanes=map0s=0:map0p=0:map1s=0:map1p=1:map2s=0:map2p=2:format=${planarYuv(bitDepth)}"
    } else {
      "setparams=colorspace=bt709,${resampled(bitDepth, coding.fullRange)}"
    }
  return PictureRoute.Planes(filter, bitDepth, reading.evaluator(bitDepth, coding.fullRange))
}

/**
 * The filter that converts a picture whose [coding] reads as a matrix swscale names, with that matrix and the range
 * [coding] records.
 *
 * A picture whose [YcbcrCoding.bitDepth] is known, and whose samples are subsampled colour, first goes through `scale`
 * to 4:4:4 samples at that depth and its own range, so the conversion to RGB starts from unsubsampled samples. Then
 * `scale` converts it with the matrix as `in_color_matrix` and the range as `in_range`, and `full_chroma_int` keeps
 * chroma at full resolution through the conversion.
 *
 * A source deeper than eight bits still carries swscale's sixteen-bit shortfall, named where [FfmpegPictureDecoder]
 * decodes.
 *
 * @throws IllegalArgumentException for a [coding] whose reading swscale does not name, which [pictureRoute] sends
 * elsewhere.
 */
internal fun ycbcrScale(coding: YcbcrCoding): String {
  val matrix = requireNotNull(swscaleMatrix(coding.reading)) { "swscale has no matrix for ${coding.reading}" }
  val bitDepth = coding.bitDepth
  // ffmpeg 9's limit: its swscale lands a subsampled twelve-bit frame only eight samples wide off the matrix, and
  // lands it right once the samples are 4:4:4.
  val hop =
    if (bitDepth != null && !coding.unsubsampled && coding.chromaFormat != MONOCHROME) {
      "${resampled(bitDepth, coding.fullRange)},"
    } else {
      ""
    }
  return "${hop}scale=in_color_matrix=$matrix:in_range=${range(coding.fullRange)}:flags=full_chroma_int"
}

private fun swscaleMatrix(reading: YcbcrReading): String? =
  (reading as? YcbcrReading.KrKb)?.equivalentCode()?.let(SWSCALE_MATRICES::get)

private fun resampled(
  bitDepth: Int,
  fullRange: Boolean,
): String {
  val range = range(fullRange)
  return "scale=in_range=$range:out_range=$range,format=${planarYuv(bitDepth)}"
}

private fun range(fullRange: Boolean): String = if (fullRange) "pc" else "tv"

// The 4:4:4 YCbCr pixel format of [bitDepth] bits a sample, little-endian past eight.
internal fun planarYuv(bitDepth: Int): String = if (bitDepth == EIGHT_BITS) "yuv444p" else "yuv444p${bitDepth}le"

// The swscale in_color_matrix name for each matrix coefficients code point equivalentCode gives for a KrKb reading.
private val SWSCALE_MATRICES =
  mapOf(
    1 to "bt709",
    4 to "fcc",
    6 to "smpte170m",
    7 to "smpte240m",
    9 to "bt2020",
  )

/**
 * Why this ffmpeg does not draw an AVIF or HEIC that names matrix coefficients [matrix], which ffmpeg cannot convert,
 * when core cannot read the depth its samples are stored at.
 */
internal fun unknownDepthReason(matrix: Int): String =
  "The picture names matrix $matrix, which ffmpeg cannot convert, and the file does not state its sample depth, so " +
    "this overlay picture cannot be drawn."

/**
 * Why this ffmpeg does not draw a picture of a codec other than AV1 and HEVC that names matrix coefficients [matrix],
 * which ffmpeg cannot convert.
 */
internal fun otherCodecDepthReason(matrix: Int): String =
  "The picture names matrix $matrix, which ffmpeg cannot convert, and filmstrip reads the sample depth it needs to " +
    "convert one itself only for AV1 and HEVC pictures, so this overlay picture cannot be drawn."

internal const val EIGHT_BITS = 8

// HEVC's chroma_format_idc for samples with no chroma.
private const val MONOCHROME = 0
