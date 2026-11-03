package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.filePathOf

/**
 * The file a source names, or null when this backend cannot read it.
 *
 * ffmpeg reads files, so a path is a path and a `file:` URI is the same file spelled with a scheme,
 * percent-decoded back into a path. Nothing else has a desktop meaning, and in-memory bytes have to
 * be written down first.
 */
internal const val READS_FILES: String =
  "This backend reads files. Use a path or a file:// URI; in-memory bytes have to be written down " +
    "before anything here can open them."

internal fun readablePath(source: MediaSource): String? =
  when (source) {
    is MediaSource.Path -> source.path
    is MediaSource.Uri -> filePathOf(source.uri) ?: source.uri.takeIf { !it.contains("://") }
    is MediaSource.Bytes -> null
    // A still names a file, but not one this backend reads as a clip, and answering with its path
    // would have ffprobe report the file's own zero duration over the one the source declares.
    is MediaSource.Image -> null
  }
