package dev.jordond.filmstrip

import dev.jordond.filmstrip.ffmpeg.ffmpegBackend

internal actual fun FilmstripBuilder.installDefaultTransform(): FilmstripBuilder = ffmpegBackend()
