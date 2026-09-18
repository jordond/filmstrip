package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import java.io.File
import java.net.URI

private const val FILE_SCHEME = "file"

/**
 * The percent-decoded path a `file:` URL names, or null when [uri] is not one this process can open
 * as a file.
 *
 * `file:/clip.mp4` and `file:///clip.mp4` both resolve to the same path. Null covers a bare path,
 * another scheme, which the JVM has no content resolver to hand over to, a string that does not
 * parse as a URI, and `file://host/clip.mp4`, which names a file on another machine rather than one
 * on this filesystem.
 */
@InternalFilmstripApi
public fun filePathOf(uri: String): String? {
  val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
  if (!FILE_SCHEME.equals(parsed.scheme, ignoreCase = true)) return null

  return runCatching { File(parsed).path }.getOrNull()
}
