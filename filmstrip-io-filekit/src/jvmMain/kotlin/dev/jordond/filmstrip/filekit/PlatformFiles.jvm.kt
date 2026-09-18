package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import java.net.URI
import java.net.URISyntaxException

private const val FILE_SCHEME = "file:"

// The desktop picker hands back a real file on disk, which is the only thing the ffmpeg backend
// reads and the only thing it writes.
public actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.of(file.absolutePath)

public actual fun PlatformFile.toImageSource(): ImageSource = ImageSource.of(file.absolutePath)

public actual fun PlatformFile.toMediaSink(): MediaSink = MediaSink.of(file.absolutePath)

/**
 * Hands an export result back to FileKit, for a save dialog or a file manager.
 *
 * A [MediaSink.Uri] is percent-decoded on the way to a path here. The ffmpeg backend does not
 * decode: it reads whatever follows `file://` as-is, so a sink you hand to an export rather than to
 * this function has to name the path already decoded.
 *
 * @throws IllegalArgumentException on a [MediaSink.Uri] that is not a `file:` URL, or is one the
 *   JVM will not read as a path, since the backend only ever writes to a path.
 * @throws IllegalStateException on [MediaSink.Temporary], which is a request rather than a
 *   location. Read the resolved path off `ExportStatus.Success.output` instead.
 */
public actual fun MediaSink.toPlatformFile(): PlatformFile =
  when (this) {
    is MediaSink.Path -> {
      PlatformFile(File(path))
    }
    is MediaSink.Uri -> {
      PlatformFile(File(fileUri(uri)))
    }
    is MediaSink.Temporary -> {
      error("A temporary sink has no location until the export resolves it.")
    }
  }

private fun fileUri(uri: String): URI {
  require(uri.startsWith(FILE_SCHEME)) { "$uri is not a file: URL." }

  return try {
    URI(uri)
  } catch (malformed: URISyntaxException) {
    throw IllegalArgumentException("$uri is not a readable file: URL.", malformed)
  }
}
