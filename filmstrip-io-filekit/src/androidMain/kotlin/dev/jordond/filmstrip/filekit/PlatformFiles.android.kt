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

/**
 * Hands an export result back to FileKit, for a share sheet or a save dialog.
 *
 * @throws IllegalStateException on [MediaSink.Temporary], which is a request rather than a
 *   location. Read the resolved path off `ExportStatus.Success.output` instead.
 */
public fun MediaSink.toPlatformFile(): PlatformFile =
  when (this) {
    is MediaSink.Path -> PlatformFile(File(path))
    is MediaSink.Uri -> PlatformFile(Uri.parse(uri))
    is MediaSink.Temporary -> error("A temporary sink has no location until the export resolves it.")
  }
