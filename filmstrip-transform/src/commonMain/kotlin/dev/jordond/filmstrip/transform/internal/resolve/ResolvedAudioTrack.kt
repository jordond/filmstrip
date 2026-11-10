package dev.jordond.filmstrip.transform.internal.resolve

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.MediaInfo

/**
 * The audio track a clip plays, settled by the planner so no backend picks one of its own.
 *
 * A backend that addresses a track by the container's id reads [id], and one that addresses it by its place among the
 * source's audio tracks reads [ordinal]. Both name the same track.
 *
 * @property track What the prober read off the track.
 * @property ordinal Where the track sits among [MediaInfo.audioTracks], counted from zero.
 */
@InternalFilmstripApi
public class ResolvedAudioTrack(
  public val track: AudioTrackInfo,
  public val ordinal: Int,
) {
  /**
   * The container's id for the track, the one a caller names it by.
   */
  public val id: AudioTrackId get() = track.id
}

/**
 * The [ResolvedAudioTrack] for [chosen] among the audio tracks of [info], or null when nothing was chosen.
 *
 * [chosen] is what [dev.jordond.filmstrip.media.clipAudioTrackOf] picked for the clip, so a track missing from [info]
 * cannot reach here.
 */
@InternalFilmstripApi
public fun resolvedAudioTrackOf(
  chosen: AudioTrackInfo?,
  info: MediaInfo,
): ResolvedAudioTrack? =
  chosen?.let { track ->
    val ordinal = info.audioTracks.indexOfFirst { it.id == track.id }
    check(ordinal >= 0) { "The chosen audio track ${track.id.value} is not one of the source's." }
    ResolvedAudioTrack(track, ordinal)
  }
