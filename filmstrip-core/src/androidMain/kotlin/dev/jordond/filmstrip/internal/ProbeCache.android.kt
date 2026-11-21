package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes this source's bytes to the cache, or says why they could not be written.
 */
internal suspend fun MediaSource.Bytes.cached(): Cached =
  withContext(Dispatchers.IO) {
    val context =
      FilmstripContext.get()
        ?: return@withContext Cached.Failed(
          FilmstripError.SourceUnreadable(source = describe(), message = FilmstripContext.MISSING_CONTEXT),
        )

    try {
      Cached.Written(AndroidScratch.fileFor(context, this@cached))
    } catch (failure: MediaBytesFileFailure) {
      Cached.Failed(FilmstripError.SourceUnreadable(source = describe(), message = failure.message))
    }
  }

/**
 * Where an in-memory source landed, or why it did not land anywhere.
 */
internal sealed interface Cached {
  class Written(
    val file: File,
  ) : Cached

  class Failed(
    val error: FilmstripError.SourceUnreadable,
  ) : Cached
}
