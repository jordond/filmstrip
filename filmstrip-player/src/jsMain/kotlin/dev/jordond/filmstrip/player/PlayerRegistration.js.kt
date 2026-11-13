package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripRegistrars
import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * Adds this artifact's registrar when the module loads.
 *
 * Exported so a bundler keeps it, since nothing else reads it.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalStdlibApi::class, ExperimentalJsExport::class)
@EagerInitialization
@JsExport
@InternalFilmstripApi
public val filmstripPlayerRegistration: Any = FilmstripRegistrars.register(PlayerRegistrar())
