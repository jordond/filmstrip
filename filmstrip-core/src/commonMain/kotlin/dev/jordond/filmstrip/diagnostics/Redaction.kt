package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource

/**
 * Reduces a path or URI to `clip-<hash>.<extension>`.
 *
 * The extension is kept because it matters for a container bug, and the hash keeps two different sources apart
 * without saying what either one is. The hash is of the whole location, so the same location always reads the same.
 *
 * @param location A path or URI.
 * @return The redacted name, without an extension when the last segment has none.
 */
@InternalFilmstripApi
public fun redactLocation(location: String): String {
  val name = location.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
  val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
  val hash = location.hashCode().toUInt().toString(HEX)
  return if (extension == null) "clip-$hash" else "clip-$hash.$extension"
}

/**
 * Rewrites every path and URI inside [text] with [redactLocation].
 *
 * A URI with a scheme, an absolute POSIX path of two or more segments, a home-relative path and a Windows drive path
 * are rewritten. A bare file name or a relative fragment like `a/b` is left as it is, since it says nothing about
 * where the caller keeps their files.
 *
 * @param text Free text such as an error message.
 * @return [text] with every location replaced.
 */
@InternalFilmstripApi
public fun redactLocations(text: String): String =
  LOCATION.replace(text) { match -> match.groupValues[1] + redactLocation(match.groupValues[2]) }

/**
 * Reads a [Throwable] and every cause under it as one line each, outermost first.
 *
 * A line is the class's simple name then its message, which is redacted with [redactLocations]. A cause that points
 * back up the chain ends it, and the chain is cut after eight levels.
 *
 * @param throwable The outermost exception.
 * @return One line per level.
 */
@InternalFilmstripApi
public fun causeChainOf(throwable: Throwable): List<String> =
  chainOf(throwable) { it.cause }.map { level ->
    val name = level::class.simpleName ?: "Throwable"
    val message = level.message?.takeIf { it.isNotBlank() }?.let(::redactLocations)
    if (message == null) name else "$name: $message"
  }

/**
 * Walks a chain of errors from [outermost] down through [next], outermost first.
 *
 * A level equal to one already read ends the chain, and the chain is cut after eight levels. A backend whose platform
 * error is not a [Throwable] reads its own chain through this, so every chain is cut the same way.
 *
 * @param outermost The error the platform reported.
 * @param next The level under one, or null at the bottom.
 * @return Every level read.
 */
@InternalFilmstripApi
public fun <T : Any> chainOf(
  outermost: T,
  next: (T) -> T?,
): List<T> {
  val seen = mutableListOf<T>()
  var current: T? = outermost
  while (current != null && seen.none { it == current } && seen.size < MAX_CAUSES) {
    seen += current
    current = next(current)
  }
  return seen
}

/**
 * Every source in this composition, in track then clip order, each reduced to a name that says nothing about where
 * it lives.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun Composition.redactedSources(): List<String> =
  tracks.flatMap { track -> track.clips.map { it.source.redactedName() } }

@OptIn(ExperimentalFilmstripApi::class)
private fun MediaSource.redactedName(): String =
  when (this) {
    is MediaSource.Path -> redactLocation(path)
    is MediaSource.Uri -> redactLocation(uri)
    is MediaSource.Bytes -> "bytes-${bytes.size}"
    is MediaSource.Image -> "image-${image.redactedName()}"
    is MediaSource.Solid -> "solid"
  }

private fun ImageSource.redactedName(): String =
  when (this) {
    is ImageSource.Path -> redactLocation(path)
    is ImageSource.Uri -> redactLocation(uri)
    is ImageSource.Bytes -> "bytes-${bytes.size}"
  }

internal const val MAX_CAUSES: Int = 8

private const val HEX = 16

// A location only starts at the start of the text or after a separator, so the tail of `a/b/c` is not read as `/b/c`.
// It never ends on punctuation, so the full stop closing a sentence stays in the sentence.
private const val TAIL = """[^\s'"<>()]*[^\s'"<>().,;:]"""

private val LOCATION =
  Regex(
    """(^|[\s'"(\[=:,])(""" +
      listOf(
        """[A-Za-z][A-Za-z0-9+.-]*://$TAIL""",
        """~/$TAIL""",
        """[A-Za-z]:\\$TAIL""",
        """/[^\s/'"<>():]+(?:/[^\s/'"<>():]+)*/[^\s/'"<>():]*[^\s/'"<>().,;:]""",
      ).joinToString("|") + ")",
  )
