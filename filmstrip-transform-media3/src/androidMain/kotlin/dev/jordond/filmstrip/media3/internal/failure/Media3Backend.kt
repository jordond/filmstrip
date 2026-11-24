package dev.jordond.filmstrip.media3.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The name media3 registers its [dev.jordond.filmstrip.diagnostics.BackendInfo] under.
 *
 * A failure the player or the export engine reports names its backend with this, so a listener can match the event
 * to the backend info.
 */
@InternalFilmstripApi
public const val MEDIA3_BACKEND: String = "media3"
