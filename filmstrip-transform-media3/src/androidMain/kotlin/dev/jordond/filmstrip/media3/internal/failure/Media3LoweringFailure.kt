package dev.jordond.filmstrip.media3.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * Why a resolved composition could not be built into a media3 one.
 *
 * Only reachable when a resolver returned something that is not a media3 `Effect`, or when a source
 * survived planning that media3 has no way to open.
 */
@InternalFilmstripApi
public class Media3LoweringFailure(
  public val reason: String,
) : Exception(reason)
