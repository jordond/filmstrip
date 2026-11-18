package dev.jordond.filmstrip.media3.internal.encoder

import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath

/**
 * The adjustment an export on [path] reports when it ran on [encoder] and that encoder spaces its key frames further
 * apart than the [spacing] the plan settled, or null when it honours it.
 *
 * `OMX.google.h264.encoder` writes an I frame at the spacing it is asked for but an IDR only every
 * [OMX_IDR_SPACING] frames, and only an IDR is a sync sample in the file. That is the encoder's limit. It is the only
 * H.264 encoder on an API 30 emulator, and the one media3 picks on some devices of that age. An [ExportPath.Copy]
 * export encodes nothing, so it never reports one.
 *
 * @param encoder The name of the video encoder the export ran on, or null where it ran on none.
 * @param spacing How many frames apart the plan places key frames, or null where it settled none.
 */
internal fun keyFrameReport(
  path: ExportPath,
  encoder: String?,
  spacing: Int?,
): Adjustment? {
  if (path == ExportPath.Copy || encoder != OMX_H264_ENCODER || spacing == null || spacing >= OMX_IDR_SPACING) {
    return null
  }
  return Adjustment(
    kind = AdjustmentKind.KeyFrameIntervalClamped,
    requested = "every $spacing frames",
    resolved = "every $OMX_IDR_SPACING frames",
    message =
      "This export ran on $OMX_H264_ENCODER, which writes a sync sample only every $OMX_IDR_SPACING frames " +
        "whatever spacing it is asked for, so the file holds one every $OMX_IDR_SPACING frames and not every " +
        "$spacing.",
  )
}

internal const val OMX_H264_ENCODER = "OMX.google.h264.encoder"

/**
 * How many frames apart [OMX_H264_ENCODER] writes an IDR, whatever key frame spacing it is asked for.
 */
internal const val OMX_IDR_SPACING = 1000
