package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.TrackHeader
import dev.jordond.filmstrip.media.movieDurationOf
import dev.jordond.filmstrip.media.trackHeadersOf
import dev.jordond.filmstrip.picture.ByteRangeRead
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSURL
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import kotlin.time.Duration

/**
 * How long the file at [url] plays for by its MP4 or QuickTime movie header, read through [movieDurationOf].
 *
 * Null when [url] is not a file URL, the file cannot be opened, or [movieDurationOf] finds no length in it.
 */
internal suspend fun movieDurationAt(url: NSURL): Duration? =
  readMovieAt(url) { size, read -> movieDurationOf(size, read) }

/**
 * The track headers of the file at [url], read through [trackHeadersOf].
 *
 * Null when [url] is not a file URL, the file cannot be opened, or [trackHeadersOf] finds no movie in it.
 */
internal suspend fun trackHeadersAt(url: NSURL): List<TrackHeader>? =
  readMovieAt(url) { size, read -> trackHeadersOf(size, read) }

@OptIn(ExperimentalForeignApi::class)
private suspend inline fun <T> readMovieAt(
  url: NSURL,
  walk: (size: Long, read: ByteRangeRead) -> T?,
): T? {
  val path = url.takeIf { it.isFileURL() }?.path ?: return null
  val descriptor = open(path, O_RDONLY)
  if (descriptor < 0) return null
  try {
    val size = memScoped { alloc<stat>().takeIf { fstat(descriptor, it.ptr) == 0 }?.st_size } ?: return null
    return walk(size) { start, count -> readAt(descriptor, start, count) }
  } finally {
    close(descriptor)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAt(
  descriptor: Int,
  start: Long,
  count: Int,
): ByteArray {
  val buffer = ByteArray(count)
  var filled = 0
  buffer.usePinned { pinned ->
    while (filled < count) {
      val read = pread(descriptor, pinned.addressOf(filled), (count - filled).convert(), start + filled).toInt()
      if (read <= 0) break
      filled += read
    }
  }
  return buffer.copyOf(filled)
}
