package dev.jordond.filmstrip

import dev.jordond.filmstrip.avfoundation.avFoundationBackend

internal actual fun FilmstripBuilder.installDefaultTransform(): FilmstripBuilder = avFoundationBackend()
