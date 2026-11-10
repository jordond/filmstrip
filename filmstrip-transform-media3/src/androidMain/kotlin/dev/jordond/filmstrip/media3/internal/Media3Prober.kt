package dev.jordond.filmstrip.media3.internal

import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.internal.MediaProberDecorator
import dev.jordond.filmstrip.internal.platformProber
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.audioLanguageOf
import dev.jordond.filmstrip.media.audioTrackIdOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.open
import dev.jordond.filmstrip.media3.internal.bounds.trackIndicesOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.MissingResourceException

/**
 * Reads a source the way core's [decorated] platform prober does, then lists its audio tracks the way media3's own
 * extractors see them.
 *
 * An export plays a clip's audio track by its place among the audio tracks media3 lists, so the list the planner
 * picks from comes from the same extractors. Each track is named through [audioTrackIdOf] by the container media3
 * read the file as. An MP4 or QuickTime file's tracks keep the default flag and language core read off their track
 * headers, since media3 reads no default flag there, and any other container's take both from media3.
 * A source media3 cannot open keeps what [decorated] read, and one [decorated] cannot read fails with its reason.
 */
internal class Media3Prober(
  override val decorated: MediaProber = platformProber(),
) : MediaProberDecorator {
  override suspend fun probe(source: MediaSource): ProbeResult {
    val probed = decorated.probe(source)
    if (probed !is ProbeResult.Success || probed.info.audioTracks.isEmpty()) return probed
    val info = probed.info
    val tracks = media3AudioTracksOf(source, info) ?: return probed
    return ProbeResult.Success(MediaInfo(info.duration, info.video, tracks, info.isExportable))
  }
}

/**
 * The audio tracks media3's extractors list in [source], in their order, or null when they cannot open it.
 *
 * @param core What core's own prober read, whose MP4 track headers give each track its default flag and language.
 */
private suspend fun media3AudioTracksOf(
  source: MediaSource,
  core: MediaInfo,
): List<AudioTrackInfo>? =
  withContext(Dispatchers.IO) {
    val extractor = MediaExtractorCompat()
    try {
      if (!extractor.open(source)) return@withContext null
      val isMovie = extractor.metrics.getString(METRICS_CONTAINER_MIME) in MOVIE_CONTAINERS
      val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
      trackIndicesOf(formats, C.TRACK_TYPE_AUDIO).mapIndexed { ordinal, index ->
        formats[index].audioTrackAt(ordinal, isMovie, core)
      }
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

private fun MediaFormat.audioTrackAt(
  ordinal: Int,
  isMovie: Boolean,
  core: MediaInfo,
): AudioTrackInfo {
  val id = audioTrackIdOf(isMovie, int(MediaFormat.KEY_TRACK_ID)?.toLong(), ordinal)
  val header = if (isMovie) core.audioTracks.firstOrNull { it.id == id } else null
  return AudioTrackInfo(
    id = id,
    codec = trackCodecOf(string(MediaFormat.KEY_MIME).orEmpty()),
    sampleRate = int(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
    channelCount = int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
    bitrate = int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.toLong()?.let(::Bitrate),
    language = header?.language ?: audioLanguageOf(string(MediaFormat.KEY_LANGUAGE)?.let(::threeLetterCodeOf)),
    isDefault = header?.isDefault ?: (int(MediaFormat.KEY_IS_DEFAULT) == 1),
  )
}

/**
 * The ISO 639-2 code of the language media3 names by [code], or [code] itself when Java knows no such code for it.
 *
 * media3 shortens every language it reads to its two letter ISO 639-1 code where one exists, and
 * [AudioTrackInfo.language] carries three letters.
 */
private fun threeLetterCodeOf(code: String): String =
  try {
    Locale.forLanguageTag(code).isO3Language.ifEmpty { code }
  } catch (unknown: MissingResourceException) {
    code
  }

private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.string(key: String): String? = if (containsKey(key)) getString(key) else null

// MediaExtractor.MetricsConstants.MIME_TYPE, which is declared above minSdk. media3 fills it with the container's type.
private const val METRICS_CONTAINER_MIME = "android.media.mediaextractor.mime"

// What media3's MP4 extractor names an MP4 or QuickTime container.
private val MOVIE_CONTAINERS = setOf(MimeTypes.VIDEO_MP4, MimeTypes.AUDIO_MP4, MimeTypes.APPLICATION_MP4)
