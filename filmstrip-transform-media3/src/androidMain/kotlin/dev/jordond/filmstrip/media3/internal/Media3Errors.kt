package dev.jordond.filmstrip.media3.internal

import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.diagnostics.CodecRole
import dev.jordond.filmstrip.diagnostics.PlatformFailure
import dev.jordond.filmstrip.diagnostics.causeChainOf
import dev.jordond.filmstrip.media3.internal.failure.MEDIA3_BACKEND
import dev.jordond.filmstrip.media3.internal.failure.causeMessages

/**
 * Classifies what media3 reported into filmstrip's own error model.
 *
 * The numeric codes are the stable part of the contract, so they are what this branches on. An
 * unrecognized one keeps its code rather than being flattened.
 *
 * The message is media3's own followed by the message of every cause under it, joined with colons. media3 only names
 * the stage that failed, such as "Muxer error", and the cause says what went wrong in it. A cause that repeats the
 * message above it is left out.
 *
 * @param codec The codec the plan asked for, named when the failure is about encoding.
 * @param sources What was being read, named when the failure is about the input. media3 does not say which source an
 * IO failure came from, so this is every source the export reads.
 * @param sink Where the output was going, named when the failure is about the destination.
 */
internal fun ExportException.toFilmstripError(
  codec: VideoCodec,
  sources: String,
  sink: String,
): FilmstripError {
  val detail = causeMessages().joinToString(": ").ifEmpty { "media3 reported error code $errorCode." }

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
    -> FilmstripError.SourceUnreadable(sources, detail)
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> FilmstripError.DecoderRejectedInput(codecInfo?.configurationFormat ?: "unknown", detail)
    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
    -> FilmstripError.NoEncoder(codec, detail)
    ExportException.ERROR_CODE_MUXING_FAILED,
    ExportException.ERROR_CODE_MUXING_APPEND,
    -> FilmstripError.SinkUnwritable(sink, detail)
    else -> FilmstripError.Underlying(errorCode, detail)
  }
}

/**
 * What media3 said about this failure, for the failure event an export reports.
 *
 * A codec failure carries the codec that was running, which side of it failed and the format it was configured with.
 * Any other failure carries only the code and the cause chain.
 */
internal fun ExportException.platformFailure(): PlatformFailure =
  PlatformFailure(
    backend = MEDIA3_BACKEND,
    code = errorCode,
    codeName = errorCodeName,
    codecName = codecInfo?.name,
    codecRole = codecInfo?.let { if (it.isDecoder) CodecRole.Decoder else CodecRole.Encoder },
    codecFormat = codecInfo?.configurationFormat,
    causes = causeChainOf(this),
  )
