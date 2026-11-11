package dev.jordond.filmstrip.media3.internal

import android.media.MediaFormat
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.internal.bounds.open
import dev.jordond.filmstrip.media3.internal.bounds.videoTrackIndex
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Where a copy's video track ends, when the copy runs on to the end of its last source, or null when it does not.
 *
 * The container of a copy gives its last frame the time the frame before it lasted. A source whose last frame lasts
 * another time is only copied faithfully when the muxer is told where the track ends. A copy that stops short of its
 * source ends on the plan's grid instead, where [copiedVideoEndOf] puts it.
 */
internal suspend fun ResolvedComposition.copiedVideoEnd(): Duration? {
  if (path != ExportPath.Copy) return null
  val track = tracks.lastOrNull { it.content != TrackContent.Audio } ?: return null
  val last = track.clips.lastOrNull() ?: return null
  val sourceEnd = videoTrackEndOf(last.source, last.end) ?: return null
  if (last.end < sourceEnd || sourceEnd <= last.start) return null

  return last.span.start + (sourceEnd - last.start)
}

/**
 * Where [source]'s video track ends, which is its declared length or the end of its last sample when that runs longer,
 * or null when it cannot be read or the track declares a length past [copyEnd], which no copy ending there runs to.
 *
 * A muxer drops every sample past the end it is told, so a container declaring no length or a short one cannot set it.
 * Only the samples from the last sync sample on are read, and from the one before it when that leaves a single sample.
 */
private suspend fun videoTrackEndOf(
  source: MediaSource,
  copyEnd: Duration,
): Duration? {
  if (source is MediaSource.Image) return null

  return withContext(Dispatchers.IO) {
    val extractor = MediaExtractorCompat()
    try {
      if (!extractor.open(source)) return@withContext null
      val track = extractor.videoTrackIndex() ?: return@withContext null
      val format = extractor.getTrackFormat(track)
      val declared = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
      if (declared > copyEnd.inWholeMicroseconds) return@withContext null
      extractor.selectTrack(track)

      var times = extractor.sampleTimesFrom(Long.MAX_VALUE)
      val opensOn = times.minOrNull() ?: return@withContext null
      if (times.size < 2 && opensOn > 0) times = extractor.sampleTimesFrom(opensOn - 1)
      trackEndOf(declared, times)?.microseconds
    } catch (unreadable: IOException) {
      null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unopened: RuntimeException) {
      null
    } finally {
      extractor.release()
    }
  }
}

// The time of every sample from the sync sample at or before time to the end of the track, in the order they are stored.
private suspend fun MediaExtractorCompat.sampleTimesFrom(time: Long): List<Long> {
  seekTo(time, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
  val times = mutableListOf<Long>()
  while (sampleTime >= 0) {
    currentCoroutineContext().ensureActive()
    times += sampleTime
    if (!advance()) break
  }
  return times
}

/**
 * Where a track ends in microseconds, given the length it declares and the [times] of its last samples in any order, or
 * null when neither says.
 *
 * The last sample ends one frame interval after it starts, the interval being the gap to the sample shown before it. A
 * lone sample has no interval and ends where it starts.
 */
internal fun trackEndOf(
  declared: Long,
  times: List<Long>,
): Long? {
  val shown = times.sortedDescending()
  val last = shown.firstOrNull() ?: return declared.takeIf { it > 0 }
  val sampled = shown.getOrNull(1)?.let { last + (last - it) } ?: last
  return maxOf(declared, sampled).takeIf { it > 0 }
}
