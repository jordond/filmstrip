package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Refuses a destination whose parent directory is missing, or null when there is one to write into.
 *
 * A relative [path] is read against the working directory, so a bare file name is checked against the directory the
 * process runs in. Android is the exception. An app process starts in `/`, which no app may write to, so there a bare
 * file name is refused outright with the platform's own message. Only the parent is looked at. Whether the process may
 * write there is left to the write itself.
 *
 * @param path The file a backend is about to write, as a filesystem path.
 * @return An [FilmstripError.SinkUnwritable] naming [path] with [SINK_PARENT_MISSING], or null when the parent is a
 * directory.
 */
@InternalFilmstripApi
public fun sinkParentRefusal(path: String): FilmstripError.SinkUnwritable? {
  val named = Path(path).parent
  if (named == null) BARE_NAME_REFUSAL?.let { return FilmstripError.SinkUnwritable(path, it) }

  val parent = named ?: Path(WORKING_DIRECTORY)
  val isDirectory =
    try {
      SystemFileSystem.metadataOrNull(parent)?.isDirectory == true
    } catch (failure: IOException) {
      return FilmstripError.SinkUnwritable(path, failure.message ?: SINK_PARENT_MISSING)
    }
  return if (isDirectory) null else FilmstripError.SinkUnwritable(path, SINK_PARENT_MISSING)
}

/**
 * The message [sinkParentRefusal] refuses a destination with, the same on every backend that asks it.
 */
@InternalFilmstripApi
public const val SINK_PARENT_MISSING: String = "The destination's parent directory does not exist."

/**
 * The message a bare file name is refused with on this platform, or null when one is read against the working
 * directory like any other relative path.
 */
internal expect val BARE_NAME_REFUSAL: String?

private const val WORKING_DIRECTORY = "."
