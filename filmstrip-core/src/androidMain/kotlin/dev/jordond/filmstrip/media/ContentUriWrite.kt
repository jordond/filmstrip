package dev.jordond.filmstrip.media

import android.content.ContentResolver
import android.net.Uri
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import java.io.IOException
import java.io.OutputStream

/**
 * Opens [uri] for writing through this resolver and hands the stream to [write], closing it once [write] returns.
 *
 * A provider that gives back no stream, an [IOException] from opening or writing, and a [SecurityException] from a
 * provider that refuses the caller all come back as an [FilmstripError.SinkUnwritable] naming [uri].
 *
 * @return null once [write] has run, or the error that stopped it.
 */
@InternalFilmstripApi
public fun ContentResolver.writeContentUri(
  uri: Uri,
  write: (OutputStream) -> Unit,
): FilmstripError.SinkUnwritable? =
  try {
    val stream = openOutputStream(uri)
    if (stream == null) {
      FilmstripError.SinkUnwritable(uri.toString(), NO_STREAM)
    } else {
      stream.use(write)
      null
    }
  } catch (failure: IOException) {
    FilmstripError.SinkUnwritable(uri.toString(), failure.message ?: UNWRITABLE)
  } catch (denied: SecurityException) {
    FilmstripError.SinkUnwritable(uri.toString(), denied.message ?: DENIED)
  }

private const val NO_STREAM = "The content provider gave back no stream to write to."

private const val UNWRITABLE = "The destination could not be written."

private const val DENIED = "Writing the destination was denied."
