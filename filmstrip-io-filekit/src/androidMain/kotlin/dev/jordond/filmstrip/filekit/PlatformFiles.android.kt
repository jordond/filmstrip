package dev.jordond.filmstrip.filekit

import android.net.Uri
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

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

// media3 writes to a path and nothing else, so a content uri destination is written to the cache
// and then streamed through the app's ContentResolver. A file one is written straight to its path.
public actual fun PlatformFile.toMediaSink(): MediaSink =
  when (val file = androidFile) {
    is AndroidFile.FileWrapper -> MediaSink.of(file.file.absolutePath)
    is AndroidFile.UriWrapper -> MediaSink.ofUri(file.uri.toString())
  }

internal actual fun platformFileAt(path: String): PlatformFile = PlatformFile(File(path))

internal actual fun platformFileOf(uri: String): PlatformFile = PlatformFile(Uri.parse(uri))
