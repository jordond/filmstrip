package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile

actual fun PlatformFile.toMediaSource(): MediaSource =
  when (val file = androidFile) {
    is AndroidFile.FileWrapper -> MediaSource.of(file.file.absolutePath)
    is AndroidFile.UriWrapper -> MediaSource.ofUri(file.uri.toString())
  }
