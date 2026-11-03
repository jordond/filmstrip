package dev.jordond.filmstrip.avfoundation.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * Why a resolved composition could not be built into an AVFoundation one.
 *
 * Only reachable when a source survived planning that AVFoundation has no way to open, or when a
 * clip's track turns out not to be there once the asset is loaded.
 */
@InternalFilmstripApi
public class AvLoweringFailure(
  public val reason: String,
) : Exception(reason)
