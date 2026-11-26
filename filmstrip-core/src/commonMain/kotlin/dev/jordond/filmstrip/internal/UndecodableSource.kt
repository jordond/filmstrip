package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The error a prober gives for [source] when the first sync sample of its first video track does not decode.
 *
 * A file whose picture data was damaged after its `moov` was written still opens and still lists every sample, and
 * only a decode of that sample tells it apart from a whole file.
 */
@InternalFilmstripApi
public fun undecodableSource(source: String): FilmstripError.SourceUnreadable =
  FilmstripError.SourceUnreadable(
    source,
    "The source's first video frame does not decode, so its picture data is damaged.",
  )

/**
 * The error a prober gives when this platform has no decoder for [codec], the codec of a source's first video track as
 * its [dev.jordond.filmstrip.media.VideoTrackInfo.codec] names it, or `unknown` when the platform cannot name it.
 *
 * Nothing tried to decode the source, so the message says nothing about whether its bytes are whole. Any other
 * platform with a decoder for [codec] may read the same file fine.
 */
@InternalFilmstripApi
public fun missingVideoDecoder(codec: String?): FilmstripError.DecoderRejectedInput =
  FilmstripError.DecoderRejectedInput(codec ?: UNKNOWN_CODEC, NO_VIDEO_DECODER)

private const val UNKNOWN_CODEC = "unknown"
private const val NO_VIDEO_DECODER =
  "This platform has no decoder for the source's video codec, so it cannot be read here."
