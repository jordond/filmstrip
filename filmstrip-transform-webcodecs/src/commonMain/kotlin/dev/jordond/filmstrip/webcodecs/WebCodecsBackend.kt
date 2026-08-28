package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber

/**
 * Registers the WebCodecs export backend, so `plan`, `export` and `capabilities` work in the
 * browser.
 *
 * @return This builder.
 */
@OptIn(InternalFilmstripApi::class)
public fun FilmstripBuilder.webCodecsBackend(): FilmstripBuilder =
  builtInEffects()
    .addExportEngineFactory { components ->
      BrowserExportEngine(components, chainedProber(components))
    }.addMediaProberFactory { BrowserProber() }
    .addBackendInfo(BackendInfo(name = "webcodecs", artifact = "dev.jordond.filmstrip:filmstrip-transform-webcodecs"))
