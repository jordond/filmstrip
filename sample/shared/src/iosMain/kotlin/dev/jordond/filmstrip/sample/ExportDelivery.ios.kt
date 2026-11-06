package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSink

internal actual fun exportSink(): MediaSink = MediaSink.temporary()

internal actual fun saveExport(output: MediaSink): Unit = Unit

internal actual fun releaseExport(output: MediaSink): Unit = Unit
