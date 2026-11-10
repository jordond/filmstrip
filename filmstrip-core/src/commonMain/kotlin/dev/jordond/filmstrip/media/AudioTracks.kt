package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import kotlin.jvm.JvmInline

/**
 * Names one audio track of a source.
 *
 * Read it off [AudioTrackInfo.id] and hand it to [Clip.audioTrack]. A track of an MP4 or QuickTime file is named by the
 * track id in its track header. A track of any other container, such as Matroska, WebM, an MPEG transport stream or a
 * raw AAC stream, is named by its place among the source's audio tracks counted from one, so the first is 1. Every
 * backend names the tracks of one file alike.
 *
 * @property value The track id in an MP4 or QuickTime track header, or else the track's place counted from one.
 */
@JvmInline
public value class AudioTrackId(
  public val value: Long,
)

/**
 * The track a clip plays from [tracks] when it names none.
 *
 * That is the first track flagged [AudioTrackInfo.isDefault], or the first track when none is flagged, or null when
 * [tracks] is empty. Every backend plays this one, so it is read here and nowhere else.
 */
@InternalFilmstripApi
public fun defaultAudioTrackOf(tracks: List<AudioTrackInfo>): AudioTrackInfo? =
  tracks.firstOrNull { it.isDefault } ?: tracks.firstOrNull()

/**
 * The audio track [clip] plays out of a source described by [info].
 *
 * The track [Clip.audioTrack] names, or the one [defaultAudioTrackOf] picks when it names none. Null when the source
 * has no audio, or when the clip names an id the source does not carry, which the planner refuses before any backend
 * asks.
 */
@InternalFilmstripApi
public fun clipAudioTrackOf(
  clip: Clip,
  info: MediaInfo,
): AudioTrackInfo? =
  when (val id = clip.audioTrack) {
    null -> defaultAudioTrackOf(info.audioTracks)
    else -> info.audioTracks.firstOrNull { it.id == id }
  }

/**
 * The language [code] a container gives a track, as [AudioTrackInfo.language] carries it.
 *
 * Null for a missing or blank code and for `und`, which is how an MP4 marks a language as undetermined. Every prober
 * reads its language through here, so all of them answer alike for the same file.
 */
@InternalFilmstripApi
public fun audioLanguageOf(code: String?): String? = code?.trim()?.takeIf { it.isNotEmpty() && it != UNDETERMINED }

/**
 * The [AudioTrackId] of the audio track at [ordinal] among a source's audio tracks.
 *
 * A track of an MP4 or QuickTime file, which [isMovie] says the source is, is named by the [trackId] in its track
 * header. A track of any other container is named by its place among the audio tracks counted from one, and so is a
 * movie track whose [trackId] is null, zero or below, since zero is reserved in a track header. Every prober names its
 * tracks through here, so one file gets the same ids on every backend.
 */
@InternalFilmstripApi
public fun audioTrackIdOf(
  isMovie: Boolean,
  trackId: Long?,
  ordinal: Int,
): AudioTrackId = AudioTrackId(trackId?.takeIf { isMovie && it > 0 } ?: (ordinal + 1L))

private const val UNDETERMINED = "und"
