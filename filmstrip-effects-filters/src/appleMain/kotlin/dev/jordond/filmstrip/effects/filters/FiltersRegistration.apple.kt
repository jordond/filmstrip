package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.FilmstripRegistrars

@Suppress("DEPRECATION")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val registration: Unit = FilmstripRegistrars.register(FiltersRegistrar())
