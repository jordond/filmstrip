package dev.jordond.filmstrip.internal

import android.media.MediaFormat
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.TrackHeader
import dev.jordond.filmstrip.media.audioLanguageOf
import dev.jordond.filmstrip.media.audioTrackIdOf
import dev.jordond.filmstrip.media.trackCodecOf
import kotlin.time.Duration

/**
 * One [AudioTrackInfo] per format in [formats], in the order the extractor lists them, which is the container's.
 *
 * An MP4's audio tracks are matched to its `soun` track headers, by the track id the extractor reports or by their
 * place among them below API 28, where it reports none. Each is named through [audioTrackIdOf] by its header's track
 * id and takes its language and default flag from there, since the extractor reports no language below API 26 and
 * never reads the enabled flag. A track of any other container, or of a file that could not be opened to look, is named
 * by its place, takes its language from the format and is not flagged as the default. The retriever can say there is
 * audio when the extractor read none of it, which keeps one track with nothing known about it.
 */
internal fun audioTracksOf(
  formats: List<MediaFormat>,
  headers: MovieHeaders,
): List<AudioTrackInfo> {
  if (formats.isEmpty()) {
    return listOf(
      AudioTrackInfo(audioTrackIdOf(isMovie = false, null, 0), trackCodecOf(""), 0, 0, null, null, isDefault = false),
    )
  }
  val isMovie = headers.tracks != null
  val sound = headers.tracks?.filter { it.isSound }.orEmpty()
  return formats.mapIndexed { ordinal, format ->
    val formatId = format.int(MediaFormat.KEY_TRACK_ID)?.toLong()
    val header = if (formatId == null) sound.getOrNull(ordinal) else sound.firstOrNull { it.id == formatId }
    AudioTrackInfo(
      id = audioTrackIdOf(isMovie, header?.id, ordinal),
      codec = trackCodecOf(format.string(MediaFormat.KEY_MIME).orEmpty()),
      // METADATA_KEY_SAMPLERATE is API 31, so the track format is the only route that
      // answers across the whole supported range and does not read zero below it.
      sampleRate = format.int(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
      channelCount = format.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
      bitrate = format.int(MediaFormat.KEY_BIT_RATE)?.toLong()?.let(::Bitrate),
      language = audioLanguageOf(header?.language ?: format.string(MediaFormat.KEY_LANGUAGE)),
      isDefault = header?.isEnabled ?: false,
    )
  }
}

/**
 * What an MP4 or QuickTime movie header and its track headers say, each null when the file gave none or could not be
 * opened to look.
 */
internal class MovieHeaders(
  val duration: Duration?,
  val tracks: List<TrackHeader>?,
) {
  companion object {
    val None = MovieHeaders(null, null)
  }
}

private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.string(key: String): String? = if (containsKey(key)) getString(key) else null
