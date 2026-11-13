package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripRegistrar
import java.util.ServiceLoader

// Loaded once, in the form R8 rewrites into direct constructor calls in a minified app.
private val loaded: List<FilmstripRegistrar> by lazy {
  ServiceLoader
    .load(FilmstripRegistrar::class.java, FilmstripRegistrar::class.java.classLoader)
    .iterator()
    .asSequence()
    .toList()
}

internal actual fun platformRegistrars(): List<FilmstripRegistrar> = loaded
