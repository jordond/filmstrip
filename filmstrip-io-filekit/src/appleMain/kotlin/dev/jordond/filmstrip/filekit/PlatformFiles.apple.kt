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

internal actual fun platformFileAt(path: String): PlatformFile = PlatformFile(NSURL.fileURLWithPath(path))

// The uri is handed to NSURL unchecked, and NSURL takes a bare path as readily as it takes a URL, so almost any string
// comes back as a file rather than as an error. Only one NSURL will not parse at all is refused.
internal actual fun platformFileOf(uri: String): PlatformFile =
  PlatformFile(NSURL.URLWithString(uri) ?: throw IllegalArgumentException("$uri is not a URL."))

// Every URL carries a path component, http ones included, so the scheme decides the arm rather
// than the path being present.
private fun NSURL.filePath(): String? = path?.takeIf { isFileURL() }
