package dev.jordond.filmstrip

import dev.jordond.filmstrip.webcodecs.webCodecsBackend

internal actual fun FilmstripBuilder.installDefaultTransform(): FilmstripBuilder = webCodecsBackend()
