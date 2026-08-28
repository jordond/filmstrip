package dev.jordond.filmstrip.media3.internal

import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.VideoCodec

/**
 * Classifies what media3 reported into filmstrip's own error model.
 *
 * The numeric codes are the stable part of the contract, so they are what this branches on. An
 * unrecognized one keeps its code rather than being flattened.
 *
 * @param codec The codec the plan asked for, named when the failure is about encoding.
 */
internal fun ExportException.toExportError(codec: VideoCodec): ExportError {
  val detail = message ?: cause?.message ?: "media3 reported error code $errorCode."

  return when (errorCode) {
    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
    ExportException.ERROR_CODE_IO_NO_PERMISSION,
    ExportException.ERROR_CODE_IO_UNSPECIFIED,
    ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    ExportException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    ExportException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    ExportException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    -> ExportError.SourceUnreadable(codecInfo?.configurationFormat ?: "source", detail)
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> ExportError.DecoderRejectedInput(codecInfo?.configurationFormat ?: "unknown", detail)
    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
    -> ExportError.NoEncoder(codec, detail)
    ExportException.ERROR_CODE_MUXING_FAILED,
    ExportException.ERROR_CODE_MUXING_APPEND,
    -> ExportError.SinkUnwritable("output", detail)
    else -> ExportError.Underlying(errorCode, detail)
  }
}
