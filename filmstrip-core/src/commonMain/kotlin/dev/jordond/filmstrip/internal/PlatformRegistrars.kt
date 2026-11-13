package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripRegistrar

// The registrars this target finds without being told, which is the ones on the classpath through ServiceLoader on
// the JVM and Android, and none elsewhere.
internal expect fun platformRegistrars(): List<FilmstripRegistrar>
