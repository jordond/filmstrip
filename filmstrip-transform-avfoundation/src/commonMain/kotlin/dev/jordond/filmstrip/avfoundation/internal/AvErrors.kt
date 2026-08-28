package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.VideoCodec
import platform.AVFoundation.AVErrorContentIsNotAuthorized
import platform.AVFoundation.AVErrorContentIsProtected
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorDecoderTemporarilyUnavailable
import platform.AVFoundation.AVErrorDiskFull
import platform.AVFoundation.AVErrorEncoderNotFound
import platform.AVFoundation.AVErrorEncoderTemporarilyUnavailable
import platform.AVFoundation.AVErrorFileFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorInvalidCompositionTrackSegmentDuration
import platform.AVFoundation.AVErrorInvalidCompositionTrackSegmentSourceDuration
import platform.AVFoundation.AVErrorInvalidCompositionTrackSegmentSourceStartTime
import platform.AVFoundation.AVErrorInvalidSourceMedia
import platform.AVFoundation.AVErrorInvalidVideoComposition
import platform.AVFoundation.AVErrorMaximumFileSizeReached
import platform.AVFoundation.AVErrorOperationNotSupportedForAsset
import platform.AVFoundation.AVErrorSessionHardwareCostOverage
import platform.AVFoundation.AVErrorUndecodableMediaData
import platform.AVFoundation.AVErrorUnsupportedOutputSettings
import platform.Foundation.NSError
import platform.Foundation.NSFileWriteNoPermissionError
import platform.Foundation.NSFileWriteOutOfSpaceError

/**
 * Which half of the pipeline reported a failure.
 *
 * A reader and a writer fail for different reasons and AVFoundation reuses codes across both.
 * `AVErrorUnknown` off the reader is a source that could not be decoded. Off the writer it is an
 * encode that did not land.
 */
internal enum class FailingSide {
  Reader,
  Writer,
}

/**
 * Classifies what AVFoundation reported into filmstrip's own error model.
 *
 * The numeric codes are the stable part of the contract, so they are what this branches on. An
 * unrecognised code falls back on [side]. [ExportError.Underlying] stays for codes from outside the
 * domains this knows.
 *
 * @param codec The codec the plan asked for, named when the failure is about encoding.
 * @param side Which half of the pipeline reported it.
 * @param source What was being read, named when the failure is about the input.
 */
internal fun NSError.toExportError(
  codec: VideoCodec,
  side: FailingSide,
  source: String,
): ExportError {
  val detail = localizedDescription.ifBlank { "AVFoundation reported error $code in $domain." }

  return when (code) {
    AVErrorOperationNotSupportedForAsset,
    AVErrorContentIsProtected,
    AVErrorContentIsNotAuthorized,
    -> {
      ExportError.SourceNotExportable(detail)
    }
    AVErrorDecodeFailed,
    AVErrorDecoderNotFound,
    AVErrorUndecodableMediaData,
    AVErrorInvalidSourceMedia,
    AVErrorFileFormatNotRecognized,
    AVErrorFileFailedToParse,
    -> {
      ExportError.DecoderRejectedInput(source, detail)
    }
    AVErrorInvalidVideoComposition,
    AVErrorInvalidCompositionTrackSegmentDuration,
    AVErrorInvalidCompositionTrackSegmentSourceStartTime,
    AVErrorInvalidCompositionTrackSegmentSourceDuration,
    -> {
      ExportError.InvalidComposition("$detail $SPANS_HINT")
    }
    AVErrorEncoderNotFound,
    AVErrorUnsupportedOutputSettings,
    -> {
      ExportError.NoEncoder(codec, detail)
    }
    // Hardware codec access is revoked, not lost. The app was suspended or another process took
    // the session. Both read the same way to a caller, who has to start the export again.
    AVErrorDecoderTemporarilyUnavailable,
    AVErrorEncoderTemporarilyUnavailable,
    AVErrorSessionHardwareCostOverage,
    -> {
      ExportError.InterruptedByBackgrounding(detail)
    }
    AVErrorDiskFull,
    AVErrorMaximumFileSizeReached,
    NSFileWriteOutOfSpaceError,
    -> {
      ExportError.InsufficientStorage(requiredBytes = null, message = detail)
    }
    NSFileWriteNoPermissionError -> {
      ExportError.SinkUnwritable(OUTPUT, detail)
    }
    else -> {
      when (side) {
        FailingSide.Reader -> ExportError.SourceUnreadable(source, detail)
        FailingSide.Writer -> ExportError.Underlying(code.toInt(), detail)
      }
    }
  }
}

private const val OUTPUT = "output"

private const val SPANS_HINT =
  "The video composition's instructions have to tile the whole timeline with no gap and no " +
    "overlap, and AVFoundation does not say which one is wrong."
