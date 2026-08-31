package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.media.MediaProber
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
      browserExportEngine(components, chainedProber(components))
    }.addMediaProberFactory { BrowserProber() }
    .addBackendInfo(BackendInfo(name = "webcodecs", artifact = "dev.jordond.filmstrip:filmstrip-transform-webcodecs"))

/**
 * Builds the engine every browser lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules rather than against two sets that
 * have to be kept in step.
 *
 * @param components The components the owning `Filmstrip` was built with.
 * @param prober Reads what each source is.
 * @return An engine that plans, resolves and exports on WebCodecs.
 */
@InternalFilmstripApi
public fun browserExportEngine(
  components: ComponentRegistry,
  prober: MediaProber,
): BrowserExportEngine = BrowserExportEngine(components, prober)
