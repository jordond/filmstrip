package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber

/**
 * Registers the WebCodecs export backend, so `probe`, `plan`, `export` and `capabilities` work in
 * the browser.
 *
 * @return This builder.
 */
public fun FilmstripBuilder.webCodecsBackend(): FilmstripBuilder =
  builtInEffects()
    .addExportEngineFactory { components -> webCodecsExportEngine(components) }
    .addMediaProberFactory { BrowserProber() }
    .addBackendInfo(BackendInfo(name = "webcodecs", artifact = "dev.jordond.filmstrip:filmstrip-transform-webcodecs"))

/**
 * Builds the engine every browser lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules.
 *
 * @param components The components the owning `Filmstrip` was built with.
 * @param prober Reads what each source is. The probers in [components] by default.
 * @param readDevice Reads what this device's encoders can do. The browser's own encoders by default.
 * @return An engine that plans, resolves and exports on WebCodecs.
 */
@InternalFilmstripApi
public fun webCodecsExportEngine(
  components: EngineComponents,
  prober: MediaProber = chainedProber(components),
  readDevice: (suspend () -> DeviceCapabilities)? = null,
): BrowserExportEngine = BrowserExportEngine(components, prober, readDevice)
