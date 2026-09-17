package dev.jordond.filmstrip.media3.internal

import androidx.media3.transformer.ExportException
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.VideoCodec
import java.util.Collections
import java.util.IdentityHashMap

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
 */
internal fun ExportException.toExportError(codec: VideoCodec): ExportError {
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

/**
 * This throwable's own message followed by the message of every cause under it, outermost first.
 *
 * A wrapper built from a bare cause copies that cause's `toString()` as its own message, so a cause whose `toString()`
 * is the message above it has nothing to add and is left out. So is a blank one, and a chain that loops back on itself
 * is walked once.
 */
@InternalFilmstripApi
public fun Throwable.causeMessages(): List<String> {
  val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
  val chain = generateSequence(this) { it.cause }.takeWhile(seen::add)

  return buildList {
    for (throwable in chain) {
      val message = throwable.message
      val above = lastOrNull()
      if (message.isNullOrBlank() || message == above || throwable.toString() == above) continue
      add(message)
    }
  }
}
