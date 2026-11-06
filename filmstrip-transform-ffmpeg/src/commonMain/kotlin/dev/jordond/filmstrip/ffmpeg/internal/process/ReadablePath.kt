package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.media.MediaBytesFile
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.filePathOf

/**
 * Why a source named by something other than a file or a buffer is refused.
 */
internal const val READS_FILES: String =
  "This backend reads files. Use a path, a file:// URI or in-memory bytes."

/**
 * The file a source names, or null when this backend cannot read it.
 *
 * ffmpeg reads files, so a path is a path and a `file:` URI is the same file spelled with a scheme, percent-decoded
 * back into a path. Bytes land in [MediaBytesFile], so a probe, every lowering and every preview seek of one buffer
 * read the one path. Nothing else has a desktop meaning.
 *
 * @param bytesRoot The directory a buffer lands under.
 * @throws MediaBytesFileFailure when bytes could not be written down.
 */
internal fun readablePath(
  source: MediaSource,
  bytesRoot: BytesRoot = DEFAULT_BYTES_ROOT,
): String? =
  when (source) {
    is MediaSource.Path -> source.path
    is MediaSource.Uri -> filePathOf(source.uri) ?: source.uri.takeIf { !it.contains("://") }
    is MediaSource.Bytes -> bytesRoot.fileFor(source)
    // A still names a file, but not one this backend reads as a clip, and answering with its path
    // would have ffprobe report the file's own zero duration over the one the source declares.
    is MediaSource.Image -> null
  }
