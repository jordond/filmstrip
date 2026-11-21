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
