package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaBytesFile
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

/**
 * Where bytes a caller handed over are written so AVFoundation can open them as a file.
 *
 * `AVURLAsset` names a URL and nothing else, so an in-memory source has to become a file before it can be probed or
 * lowered. This roots [MediaBytesFile] in the temporary directory.
 */
@InternalFilmstripApi
public object AppleScratch {
  /**
   * Why a buffer written under the name [extension] holds no track AVFoundation can read.
   *
   * It says what was tried and nothing more, because a corrupt file reads the same as a container the platform doesn't
   * open.
   */
  public fun unreadableUnder(extension: String): String =
    "AVFoundation found no track it can read in these bytes, written to a file named .$extension."

  /**
   * The URL of the file holding [source]'s bytes.
   *
   * @param root The directory the file lands under.
   * @throws MediaBytesFileFailure when the directory or the file could not be written.
   */
  public fun urlFor(
    source: MediaSource.Bytes,
    root: String = defaultRoot,
  ): NSURL = NSURL.fileURLWithPath(MediaBytesFile.fileFor(root, source))

  /**
   * The directory a buffer lands under unless a caller names another, which is the temporary directory.
   */
  public val defaultRoot: String get() = NSTemporaryDirectory()
}
