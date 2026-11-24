package dev.jordond.filmstrip.avfoundation.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The name AVFoundation registers its [dev.jordond.filmstrip.diagnostics.BackendInfo] under.
 *
 * A failure the player or the export engine reports names its backend with this, so a listener can match the event
 * to the backend info.
 */
@InternalFilmstripApi
public const val AVFOUNDATION_BACKEND: String = "avfoundation"
