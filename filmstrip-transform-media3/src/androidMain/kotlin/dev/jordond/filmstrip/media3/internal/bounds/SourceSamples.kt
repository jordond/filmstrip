package dev.jordond.filmstrip.media3.internal.bounds

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.internal.AndroidScratch
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The samples a clip of [source] from [start] to [end] can show, or null when its container cannot be read or holds no
 * video sample there.
 *
 * The extractor walks samples in decode order, so the walk opens on the sync sample before [start] and reads on past
 * the end until a second sync sample beyond it, which is as far as a frame can be reordered. [end] is the clip's
 * planned end, which the planner already holds to the source's probed length. The last sample read stops showing at
 * that second sync sample, or where the video track ends when the walk runs out first. A track naming no end of its own
 * runs one frame interval past its last sample.
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
      val trackEnd = extractor.getTrackFormat(track).durationUs()
      extractor.selectTrack(track)

      val cut = end.inWholeMicroseconds
      extractor.seekTo(start.inWholeMicroseconds, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val read = mutableListOf<Long>()
      val stoppedOn =
        extractor.walkUntilSecondSyncFrom(cut) {
          ensureActive()
          read += it
        }
      val samples = read.distinct().sorted()
      val last = samples.lastOrNull() ?: return@withContext null
      val interval = samples.getOrNull(samples.lastIndex - 1)?.let { last - it }
      val shownUntil =
        listOfNotNull(stoppedOn, trackEnd, interval?.let { last + it }).firstOrNull { it > last }
          ?: return@withContext null
      FrameBounds(samples.map { it.microseconds }, shownUntil.microseconds)
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
 * Where a clip of [source]'s audio trimmed to [start] opens its decode, which is the access unit before the one holding
 * [start], and the encoder delay its audio track names. Null when its container cannot be read or the audio track at
 * [audioOrdinal] holds no sample at or before [start].
 *
 * The AAC decoder fades in the first unit it is fed, so a clip opened on the unit holding its start would fade in over
 * its first frames. The unit before it decodes into that fade and is dropped with the rest of the lead. A start inside
 * the track's first unit has nothing before it and opens on that unit.
 *
 * The units are timed from the head of the track, encoder delay included, so the unit holding [start] is the one
 * holding [start] plus the delay. The time is read with media3's own extractor, so it is the time media3 gives the unit
 * when it decodes the clip. An item clipped from exactly that time keeps the unit, where one clipped anywhere inside it
 * drops it.
 */
internal suspend fun audioPrerollStartOf(
  source: MediaSource,
  start: Duration,
  audioOrdinal: Int,
): AudioPreroll? =
  withContext(Dispatchers.IO) {
    val extractor = MediaExtractorCompat()
    try {
      if (!extractor.open(source)) return@withContext null
      val track = extractor.audioTrackIndex(audioOrdinal) ?: return@withContext null
      val delay = extractor.getTrackFormat(track).encoderDelay()
      extractor.selectTrack(track)
      val target = (start + delay).inWholeMicroseconds
      extractor.seekTo(target, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
      if (extractor.sampleTrackIndex != track) return@withContext null
      // Every AAC unit is a sync sample, so seeking a microsecond before the holding unit lands on the one before it.
      extractor.seekTo(maxOf(extractor.sampleTime - 1, 0L), MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
      // Only the holding unit and the one before it decide the preroll.
      val units = ArrayDeque<Long>(2)
      while (extractor.sampleTrackIndex == track && extractor.sampleTime <= target) {
        ensureActive()
        if (units.size == 2) units.removeFirst()
        units.addLast(extractor.sampleTime)
        if (!extractor.advance()) break
      }
      prerollUnitOf(units, target)?.let { AudioPreroll(it.microseconds, delay) }
    } catch (unwritable: MediaBytesFileFailure) {
      // Thrown when a source handed over as bytes could not be written to the cache.
      null
    } catch (unreadable: IOException) {
      null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unopened: RuntimeException) {
      // setDataSource throws a bare RuntimeException for a container the extractor cannot make sense of.
      null
    } finally {
      extractor.release()
    }
  }

/**
 * The unit a clip trimmed to [start] opens its decode on, out of the ascending unit start times in [units], or null
 * when none starts at or before [start].
 *
 * That is the unit before the one holding [start], or the holding unit itself when it is the first in [units].
 */
internal fun prerollUnitOf(
  units: List<Long>,
  start: Long,
): Long? {
  val holding = units.indexOfLast { it <= start }
  return if (holding < 0) null else units[maxOf(holding - 1, 0)]
}

/**
 * The H.273 color primaries code point of the SD color standard [source]'s video track names, or null when it names
 * another standard or none, or its container cannot be read.
 */
internal suspend fun sdPrimariesNamedBy(source: MediaSource): Int? =
  withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
      if (!extractor.open(source)) return@withContext null
      val track = extractor.videoTrackIndex() ?: return@withContext null
      val format = extractor.getTrackFormat(track)
      if (!format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) return@withContext null
      when (format.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> SMPTE_C_PRIMARIES
        MediaFormat.COLOR_STANDARD_BT601_PAL -> EBU_PRIMARIES
        else -> null
      }
    } catch (unreadable: IOException) {
      // Thrown when a source handed over as bytes could not be written to the cache.
      null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unopened: RuntimeException) {
      // setDataSource throws a bare RuntimeException for a container the extractor cannot make sense of.
      null
    } finally {
      extractor.release()
    }
  }

/**
 * Hands [visit] every sample from where the extractor stands, up to the second sync sample at or after [time] or the
 * end of the track.
 *
 * @return the time of the second sync sample the walk stopped on, or null when it ran to the end of the track.
 */
private inline fun MediaExtractor.walkUntilSecondSyncFrom(
  time: Long,
  visit: (Long) -> Unit,
): Long? {
  var syncs = 0
  while (sampleTime >= 0) {
    val sample = sampleTime
    if (sample >= time && (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0 && ++syncs == 2) return sample
    visit(sample)
    if (!advance()) return null
  }
  return null
}

// How long a track runs, in microseconds, or null when its format doesn't say.
private fun MediaFormat.durationUs(): Long? =
  takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)

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
 * Points this extractor at [source] the way [MediaExtractor.open] does, or returns false when there is no container to
 * point it at.
 *
 * A path and the cached copy of bytes open as file uris. The extractor parses a bare path as a uri, which would read a
 * `#`, a `?` or a `%` in a file name as part of the uri and open another file.
 */
internal fun MediaExtractorCompat.open(source: MediaSource): Boolean {
  when (source) {
    is MediaSource.Path -> {
      setDataSource(Uri.fromFile(File(source.path)), 0L)
    }
    is MediaSource.Uri -> {
      setDataSource(FilmstripContext.get() ?: return false, Uri.parse(source.uri), null)
    }
    is MediaSource.Bytes -> {
      setDataSource(Uri.fromFile(AndroidScratch.fileFor(FilmstripContext.get() ?: return false, source)), 0L)
    }
    is MediaSource.Image -> {
      return false
    }
  }
  return true
}

/**
 * How long the encoder delay this audio format names runs, rounded up to a whole microsecond, or zero when it names
 * none or no sample rate to count it at.
 */
private fun MediaFormat.encoderDelay(): Duration {
  val frames = if (containsKey(MediaFormat.KEY_ENCODER_DELAY)) getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
  val rate = if (containsKey(MediaFormat.KEY_SAMPLE_RATE)) getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
  if (frames <= 0 || rate <= 0) return Duration.ZERO
  return ((frames * MICROS_PER_SECOND + rate - 1) / rate).microseconds
}

private const val MICROS_PER_SECOND = 1_000_000L

/**
 * The index among this extractor's tracks of the audio track at [ordinal] among its audio tracks, which is the one
 * [ChosenTracks.audioOrdinal] has an export decode, or null when it opened no container carrying that many.
 */
private fun MediaExtractorCompat.audioTrackIndex(ordinal: Int): Int? =
  trackIndexOf((0 until trackCount).map { getTrackFormat(it) }, C.TRACK_TYPE_AUDIO, ordinal)

/**
 * The index of the first video track among this extractor's tracks, which is the one [ChosenTracks] has an export
 * decode, or null when it opened no container carrying one.
 */
internal fun MediaExtractor.videoTrackIndex(): Int? =
  trackIndexOf((0 until trackCount).map { getTrackFormat(it) }, C.TRACK_TYPE_VIDEO, 0)
