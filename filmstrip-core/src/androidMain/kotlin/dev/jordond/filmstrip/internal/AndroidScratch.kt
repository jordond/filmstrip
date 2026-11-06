package dev.jordond.filmstrip.internal

import android.content.Context
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaBytesFile
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import java.io.File

/**
 * Where bytes a caller handed over are written so the platform can open them as a file.
 *
 * `MediaMetadataRetriever` and `MediaItem` both name a path or a URI and nothing else, so an in-memory source has to
 * become a file before it can be probed or lowered. This roots [MediaBytesFile] in the app's cache directory.
 */
@InternalFilmstripApi
public object AndroidScratch {
  /**
   * The file under [context]'s cache holding [source]'s bytes.
   *
   * Every caller that needs [source] as a file goes through here, so a source probed and then exported is written to
   * one file rather than two.
   *
   * @throws MediaBytesFileFailure when the directory or the file could not be written.
   */
  public fun fileFor(
    context: Context,
    source: MediaSource.Bytes,
  ): File = File(MediaBytesFile.fileFor(context.cacheDir.path, source))

  /**
   * The file under [context]'s cache holding [bytes], written under [extension] if it is not there already.
   *
   * @throws MediaBytesFileFailure when the directory or the file could not be written.
   */
  public fun fileFor(
    context: Context,
    bytes: ByteArray,
    extension: String,
  ): File = File(MediaBytesFile.fileFor(context.cacheDir.path, bytes, extension))
}
