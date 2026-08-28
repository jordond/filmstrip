package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile

public actual fun PlatformFile.toMediaSource(): MediaSource =
  when (val file = androidFile) {
    is AndroidFile.FileWrapper -> MediaSource.of(file.file.absolutePath)
    is AndroidFile.UriWrapper -> MediaSource.ofUri(file.uri.toString())
  }

public actual fun PlatformFile.toImageSource(): ImageSource =
  when (val file = androidFile) {
    is AndroidFile.FileWrapper -> ImageSource.of(file.file.absolutePath)
    is AndroidFile.UriWrapper -> ImageSource.ofUri(file.uri.toString())
  }
