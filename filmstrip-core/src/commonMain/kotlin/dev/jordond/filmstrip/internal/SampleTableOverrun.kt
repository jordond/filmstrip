package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.lastSampleEndOf

/**
 * How many bytes a source of [byteLength] bytes is missing for its last sample to end where its sample table says,
 * at [lastSampleEnd], or null when that sample ends inside the source.
 */
internal fun sampleTableOverrun(
  lastSampleEnd: Long,
  byteLength: Long,
): Long? = (lastSampleEnd - byteLength).takeIf { it > 0 }

/**
 * How many bytes an MP4 or QuickTime file of [size] bytes is missing for every track's last sample to fit, or null
 * when they all fit or the file has no sample table [lastSampleEndOf] can read.
 *
 * A file cut short after its `moov` was written, such as a recording stopped part way through its `mdat`, still opens
 * and still lists every sample, so only this check tells it apart from a whole file.
 *
 * @param read Reads up to `count` bytes of the file starting at `start`, handing back fewer when the file ends first.
 */
@InternalFilmstripApi
public suspend fun sampleTableOverrunOf(
  size: Long,
  read: suspend (start: Long, count: Int) -> ByteArray,
): Long? = lastSampleEndOf(size, read)?.let { sampleTableOverrun(it, size) }

/**
 * The error a prober gives for [source] when it is [missing] bytes short of its last sample, as
 * [sampleTableOverrunOf] counts them.
 */
@InternalFilmstripApi
public fun truncatedSource(
  source: String,
  missing: Long,
): FilmstripError.SourceUnreadable =
  FilmstripError.SourceUnreadable(source, "The source ends $missing bytes before its last sample, so it was cut short.")
