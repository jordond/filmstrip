package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.effects.builtInEffects

/**
 * Registers the preview backend, so `preview` and effect-applied frames work.
 *
 * Without it `preview` returns a player whose state reports the missing artifact. The built-in
 * effect catalogue is registered too, and registering that twice is harmless.
 */
public fun FilmstripBuilder.playerBackend(): FilmstripBuilder =
  builtInEffects()
    .addPlayerEngineFactory { config -> createPlayerEngine(config) }
    .addThumbnailSourceFactory { request -> createThumbnailSource(request) }
