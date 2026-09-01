package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * Why a backend that decodes frames from a video track refuses a still.
 *
 * ffmpeg and the browser pipeline both open a clip this way, and a photo has no track to decode.
 * The reason is identical on both, so it is written once and each backend names itself in it.
 *
 * @param backend The name of the backend refusing the clip.
 */
@InternalFilmstripApi
public fun stillUnsupportedMessage(backend: String): String =
  "The $backend backend does not put stills on the timeline. It decodes frames from a video " +
    "track, and a photo has none."
