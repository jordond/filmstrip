package dev.jordond.filmstrip

import dev.jordond.filmstrip.media3.media3Backend

internal actual fun FilmstripBuilder.installDefaultTransform(): FilmstripBuilder = media3Backend()
