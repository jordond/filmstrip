package dev.jordond.filmstrip.media3.internal.bounds

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.internal.AndroidScratch
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The samples [source] decodes between [start] and [end], or null when its container cannot be read or holds no video
 * sample in that window.
 *
 * The extractor walks samples in decode order, so the earliest and the latest in presentation order are found by
 * reading on past each end until a second sync sample beyond it, which is as far as a frame can be reordered.
 */
internal suspend fun frameBoundsOf(
  source: MediaSource,
  start: Duration,
  end: Duration,
): FrameBounds? =
  withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
      if (!extractor.open(source)) return@withContext null
      val track = extractor.videoTrackIndex() ?: return@withContext null
      val sourceEnd = extractor.longestTrackUs()
      extractor.selectTrack(track)

      val cut = sourceEnd?.let { minOf(end.inWholeMicroseconds, it) } ?: end.inWholeMicroseconds
      val first = extractor.earliestSample(start.inWholeMicroseconds, cut) ?: return@withContext null
      val last = extractor.latestSampleBefore(cut) ?: return@withContext null
      FrameBounds(first = first.microseconds, last = last.microseconds, end = cut.microseconds)
    } catch (unwritable: MediaBytesFileFailure) {
      // Thrown when a source handed over as bytes could not be written to the cache.
      null
    } catch (unreadable: IOException) {
      null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unopened: RuntimeException) {
      // setDataSource and seekTo both throw a bare RuntimeException for a container or a track
      // the extractor cannot make sense of.
      null
    } finally {
      extractor.release()
    }
  }

/**
 * The earliest sample at or after [from] and before [until], in presentation order.
 */
private fun MediaExtractor.earliestSample(
  from: Long,
  until: Long,
): Long? {
  seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
  var earliest = Long.MAX_VALUE
  walkUntilSecondSyncFrom(from) { time ->
    if (time in from..<until) earliest = minOf(earliest, time)
  }
  return earliest.takeIf { it != Long.MAX_VALUE }
}

/**
 * The latest sample before [until], in presentation order.
 *
 * Opened on the sync sample before [until] rather than at it, since a cut that sits exactly on a sync sample closes on
 * a frame of the group before.
 */
private fun MediaExtractor.latestSampleBefore(until: Long): Long? {
  seekTo(until - 1, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
  var latest = Long.MIN_VALUE
  walkUntilSecondSyncFrom(until) { time ->
    if (time < until) latest = maxOf(latest, time)
  }
  return latest.takeIf { it != Long.MIN_VALUE }
}

/**
 * Hands [visit] every sample from where the extractor stands, up to the second sync sample at or after [time] or the
 * end of the track.
 */
private inline fun MediaExtractor.walkUntilSecondSyncFrom(
  time: Long,
  visit: (Long) -> Unit,
) {
  var syncs = 0
  while (sampleTime >= 0) {
    val sample = sampleTime
    if (sample >= time && (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0 && ++syncs == 2) return
    visit(sample)
    if (!advance()) return
  }
}

/**
 * How long this extractor's longest track runs, in microseconds, which is where media3 ends a clip that asks for more
 * than the source holds. Null when no track says.
 */
private fun MediaExtractor.longestTrackUs(): Long? =
  (0 until trackCount)
    .mapNotNull { index ->
      getTrackFormat(index).takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
    }.maxOrNull()

/**
 * Points this extractor at [source], or returns false when there is no container to point it at.
 *
 * Bytes are read from the cache, under the same name an export or a probe of the same source lands on, so this never
 * writes a second copy of them. A path opens without a context. A uri and an in-memory source resolve through the
 * installed one and open nothing without it. A plan never reads that answer, because probing either source has already
 * failed with [FilmstripContext.MISSING_CONTEXT] by then.
 */
internal fun MediaExtractor.open(source: MediaSource): Boolean {
  when (source) {
    is MediaSource.Path -> {
      setDataSource(source.path)
    }
    is MediaSource.Uri -> {
      setDataSource(FilmstripContext.get() ?: return false, Uri.parse(source.uri), null)
    }
    is MediaSource.Bytes -> {
      setDataSource(AndroidScratch.fileFor(FilmstripContext.get() ?: return false, source).path)
    }
    // A still carries no container, and so no sync sample of its own to read.
    is MediaSource.Image -> {
      return false
    }
  }
  return true
}

/**
 * The index of this extractor's first video track, or null when it opened no container carrying
 * one.
 */
internal fun MediaExtractor.videoTrackIndex(): Int? {
  for (index in 0 until trackCount) {
    val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME)
    if (mime?.startsWith("video/") == true) return index
  }
  return null
}
