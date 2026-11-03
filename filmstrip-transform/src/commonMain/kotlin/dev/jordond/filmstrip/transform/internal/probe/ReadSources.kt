package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * The source an [FilmstripError.SourceUnreadable] names when the backend reports a failed read without saying which
 * source it was reading.
 *
 * Every distinct source the tracks lay, in the order they are first laid, each described the way a prober names it
 * and joined with commas. An export of one source names just that source.
 */
@InternalFilmstripApi
public val ResolvedComposition.readSources: String
  get() =
    tracks
      .flatMap { track -> track.clips.map { it.source } }
      .distinct()
      .joinToString(", ") { it.describe() }
