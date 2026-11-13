package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripRegistrars

@Suppress("DEPRECATION")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val registration: Unit = FilmstripRegistrars.register(PlayerRegistrar())
