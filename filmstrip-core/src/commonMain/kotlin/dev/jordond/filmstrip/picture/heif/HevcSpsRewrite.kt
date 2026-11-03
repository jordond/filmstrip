package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.ChromaFormat
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.IDENTITY_MATRIX
import dev.jordond.filmstrip.picture.bytes.BitWriter
import dev.jordond.filmstrip.picture.bytes.malformed

// Rewrites the video signal type an HEVC sequence parameter set carries, following ITU-T H.265 section 7.3.2.2.1 and
// Annex E.2.1, by splicing bits at the positions the reader in HevcSps.kt finds.

/**
 * [nal], a whole sequence parameter set NAL unit, with its video usability information stating [target]'s primaries,
 * transfer, matrix and range, or null when it does not parse or the rewritten unit does not read back that way.
 *
 * Gives [nal] itself when it already states all four of [target]'s fields. Without video usability information the unit
 * gains a minimal one, every flag clear but the video signal type, whose `video_format` is 5 for unspecified. With it,
 * only the video signal type and its color description are replaced, keeping an existing `video_format`. Every other
 * bit is copied as it was, the RBSP stop bit is realigned and the emulation prevention bytes are written again.
 *
 * The rewritten unit is read back, and must give [target] with its video usability information starting at the same
 * bit, so every field before it is unchanged.
 *
 * Null too when [target] names the identity matrix 0 and the unit's own `chroma_format_idc` is not 4:4:4, the only
 * layout H.265 allows it on, whatever the `hvcC` around it says.
 *
 * @param target The signal to state, every field set, each code point in 0 to 255.
 */
internal fun spsStating(
  nal: ByteArray,
  target: CodestreamSignal,
): ByteArray? {
  val rbsp = unescape(nal)
  val layout = spsLayout(rbsp) ?: return null
  if (target.matrix == IDENTITY_MATRIX && layout.chromaFormat != ChromaFormat.YUV444) return null
  if (layout.signal == target) return nal
  val stop = stopBit(rbsp)
  val resumeAt = if (layout.signalType < 0) layout.vuiFlag + 1 else layout.signalTypeEnd
  if (stop < resumeAt) return null
  val out = BitWriter(rbsp.size + MINIMAL_VUI_BYTES)
  if (layout.signalType < 0) {
    out.copy(rbsp, 0, layout.vuiFlag)
    out.write(1, 1)
    out.write(2, 0)
    out.signalType(UNSPECIFIED_VIDEO_FORMAT, target)
    out.write(FLAGS_AFTER_SIGNAL_TYPE, 0)
  } else {
    out.copy(rbsp, 0, layout.signalType)
    out.signalType(layout.videoFormat ?: UNSPECIFIED_VIDEO_FORMAT, target)
  }
  out.copy(rbsp, resumeAt, stop + 1)
  val rewritten = withEmulationPrevention(out.aligned())
  val check = spsLayout(unescape(rewritten)) ?: return null
  val signalTypeAt = if (layout.signalType < 0) layout.vuiFlag + MINIMAL_VUI_LEAD else layout.signalType
  val readsBack = check.signal == target && check.vuiFlag == layout.vuiFlag && check.signalType == signalTypeAt
  return if (readsBack) rewritten else null
}

private fun BitWriter.signalType(
  videoFormat: Int,
  target: CodestreamSignal,
) {
  write(1, 1)
  write(3, videoFormat)
  write(1, if (target.fullRange == true) 1 else 0)
  write(1, 1)
  write(8, target.primaries ?: malformed())
  write(8, target.transfer ?: malformed())
  write(8, target.matrix ?: malformed())
}

// The position of the RBSP stop bit, the last set bit, or -1 when every bit is clear.
private fun stopBit(rbsp: ByteArray): Long {
  for (at in rbsp.indices.reversed()) {
    val byte = rbsp[at].toInt() and 0xFF
    if (byte != 0) return at * 8L + 7 - byte.countTrailingZeroBits()
  }
  return -1
}

/**
 * [rbsp] with an emulation prevention byte before every byte of 3 or less that follows two zero bytes, as a NAL unit
 * carries it.
 */
internal fun withEmulationPrevention(rbsp: ByteArray): ByteArray {
  val out = ByteArray(rbsp.size + rbsp.size / 2 + 1)
  var size = 0
  var zeros = 0
  for (byte in rbsp) {
    val value = byte.toInt() and 0xFF
    if (zeros >= 2 && value <= 3) {
      out[size++] = 3
      zeros = 0
    }
    out[size++] = byte
    zeros = if (value == 0) zeros + 1 else 0
  }
  return out.copyOf(size)
}

private const val UNSPECIFIED_VIDEO_FORMAT = 5

// vui_parameters_present_flag, aspect_ratio_info_present_flag and overscan_info_present_flag, ahead of the signal type.
private const val MINIMAL_VUI_LEAD = 3

// chroma_loc_info_present_flag through bitstream_restriction_flag, the seven flags a minimal VUI leaves clear.
private const val FLAGS_AFTER_SIGNAL_TYPE = 7

private const val MINIMAL_VUI_BYTES = 6
