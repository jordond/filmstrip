package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSink

public actual fun exportSink(): MediaSink = MediaSink.temporary()

public actual fun saveExport(output: MediaSink): Unit = Unit

public actual fun releaseExport(output: MediaSink): Unit = Unit
