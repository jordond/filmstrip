package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import platform.Foundation.NSURL

/**
 * Reads this file as video.
 *
 * A URL the document picker returned is security-scoped and FileKit does not hold that scope open,
 * so wrap the export in `withScopedAccess` or the backend opens nothing. A URL from the photo
 * picker is a copy in the app's temporary directory and needs no scope.
 */
public actual fun PlatformFile.toMediaSource(): MediaSource =
  nsUrl.filePath()?.let(MediaSource::of) ?: MediaSource.ofUri(nsUrl.absoluteString.orEmpty())

public actual fun PlatformFile.toImageSource(): ImageSource =
  nsUrl.filePath()?.let(ImageSource::of) ?: ImageSource.ofUri(nsUrl.absoluteString.orEmpty())

// AVFoundation writes to a file url and refuses anything else by name, so a URL that is not one
// stays a uri and fails where the caller can read which URL it was.
public actual fun PlatformFile.toMediaSink(): MediaSink =
  nsUrl.filePath()?.let(MediaSink::of) ?: MediaSink.ofUri(nsUrl.absoluteString.orEmpty())

/**
 * Hands an export result back to FileKit, for a share sheet or a save dialog.
 *
 * A [MediaSink.Uri] is handed to `NSURL` unchecked, and `NSURL` takes a bare path as readily as it
 * takes a URL, so almost any string comes back as a file rather than as an error.
 *
 * @throws IllegalArgumentException on a [MediaSink.Uri] that `NSURL` will not parse at all.
 * @throws IllegalStateException on [MediaSink.Temporary], which is a request rather than a
 *   location. Read the resolved path off `ExportStatus.Success.output` instead.
 */
public actual fun MediaSink.toPlatformFile(): PlatformFile =
  when (this) {
    is MediaSink.Path -> {
      PlatformFile(NSURL.fileURLWithPath(path))
    }
    is MediaSink.Uri -> {
      PlatformFile(NSURL.URLWithString(uri) ?: throw IllegalArgumentException("$uri is not a URL."))
    }
    is MediaSink.Temporary -> {
      error("A temporary sink has no location until the export resolves it.")
    }
  }

// Every URL carries a path component, http ones included, so the scheme decides the arm rather
// than the path being present.
private fun NSURL.filePath(): String? = path?.takeIf { isFileURL() }
