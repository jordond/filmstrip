package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectScope
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.outputHeightRefusal
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import kotlin.time.Duration

/**
 * Which of the output's streams survive what the composition and the spec ask for.
 */
internal class Streams(
  val keepVideo: Boolean,
  val keepAudio: Boolean,
)

/**
 * What each scope asks for can cancel out: an audio-only output over a video-only track, or a
 * kept-audio output over a source that carries none, leaves no track to write.
 */
internal fun streamsOf(
  edit: EditComposition,
  spec: ExportSpec,
  infos: Map<MediaSource, MediaInfo>,
): Streams {
  val anySourceAudio = edit.tracks.any { track -> track.clips.any { infos[it.source]?.audio != null } }
  return Streams(
    keepVideo = edit.audio != AudioSpec.AudioOnly,
    keepAudio = spec.audioCodec != AudioCodec.None && edit.audio != AudioSpec.Remove && anySourceAudio,
  )
}

/**
 * The first refusal that reads only the shape of [edit] and what each source is, before anything
 * is sized or encoded, or null when there is none.
 */
internal fun structuralRefusal(
  edit: EditComposition,
  spec: ExportSpec,
  infos: Map<MediaSource, MediaInfo>,
  streams: Streams,
): FilmstripError? {
  val primary = edit.tracks.firstOrNull() ?: return invalid(NO_TRACKS)

  if (edit.tracks.drop(1).any { it.content != TrackContent.Audio }) return invalid(SECOND_VIDEO_TRACK)
  if (primary.clips.isEmpty()) return invalid(PRIMARY_HAS_NO_CLIPS)
  if (primary.content == TrackContent.Audio) return invalid(NO_PRIMARY_VIDEO)
  if (edit.tracks.all { it.looping }) return invalid(EVERY_TRACK_LOOPS)
  if (edit.tracks.any { it.start < Duration.ZERO }) return invalid(TRACK_STARTS_EARLY)
  spec.frameRate?.let { if (it <= 0) return invalid(frameRateNotPositive(it)) }
  outputHeightRefusal(spec.heightPx)?.let { return it }
  if (spec.bitrate != null && spec.quality != null) return invalid(BITRATE_AND_QUALITY)
  if (edit.tracks.none { it.contributes(streams.keepAudio, streams.keepVideo) }) return invalid(NOTHING_TO_ENCODE)

  edit.tracks.flatMap { it.clips }.forEach { clip ->
    val info = infos[clip.source] ?: return FilmstripError.SourceUnreadable(clip.source.describe(), UNREADABLE)
    if (!info.isExportable) {
      return FilmstripError.SourceNotExportable("${clip.source.describe()} is protected and cannot be exported.")
    }
  }

  // A clip-only effect reads where a frame sits inside the run it is drawn over. A track effect and
  // a composition-level geometry effect are lowered by fanning onto each clip on the backends that
  // have no pass of their own to run them in, so the same declaration would draw one run on one
  // engine and a run per clip on another.
  val misplaced =
    (edit.effects + edit.tracks.flatMap { it.effects }).firstOrNull { it.scope == EffectScope.ClipOnly }
  if (misplaced != null) return FilmstripError.UnsupportedEffect(misplaced.id, CLIP_ONLY_SCOPE)

  // A looping track lays its clips down again from the top, so a clip on one holds more than one
  // slot on the timeline and there is no single run for a clip-only effect to measure against.
  val looped =
    edit.tracks
      .filter { it.looping }
      .flatMap { track -> track.clips.flatMap { it.effects } }
      .firstOrNull { it.scope == EffectScope.ClipOnly }
  if (looped != null) return FilmstripError.UnsupportedEffect(looped.id, CLIP_ONLY_LOOPING)
  return null
}

/**
 * A refusal of the composition itself, worded by [message].
 */
internal fun invalid(message: String): FilmstripError = FilmstripError.InvalidComposition(message)

internal const val NO_PRIMARY_VIDEO = "The primary track contributes no video, so there is nothing to encode."

/**
 * Whether this track still writes something once the output's audio and video have been settled.
 */
private fun Track.contributes(
  keepAudio: Boolean,
  keepVideo: Boolean,
): Boolean = (keepVideo && content != TrackContent.Audio) || (keepAudio && content != TrackContent.Video)

private fun frameRateNotPositive(rate: Int): String =
  "The export asks for $rate fps, and a frame rate has to be positive. Leave it unset to keep the source's rate."

private const val BITRATE_AND_QUALITY =
  "The export names both a bitrate and a quality, and only one of them can set the rate. Leave the other unset."

private const val NO_TRACKS = "A composition needs at least one track."

private const val SECOND_VIDEO_TRACK =
  "This backend renders video from the primary track only. A second video track needs a " +
    "compositor, which has not landed here. A second audio-only track works."

private const val PRIMARY_HAS_NO_CLIPS = "The primary track has no clips."

private const val NOTHING_TO_ENCODE =
  "No track survives what the composition and the spec ask for, so the export would write a " +
    "file with no tracks in it."

private const val EVERY_TRACK_LOOPS = "Every track loops, so the composition has nothing to bound it."

private const val TRACK_STARTS_EARLY =
  "A track's start is negative, so it would begin before the composition does. A start is " +
    "measured from the start of the composition and has to be zero or later."

private const val UNREADABLE = "The source could not be read, so there is nothing to plan against."

private const val CLIP_ONLY_SCOPE =
  "This effect reads where a frame sits inside the run it is drawn over, so it belongs on a " +
    "clip. Declared on a track or on the composition it would draw one run on one engine and " +
    "a run per clip on another. Move it onto the clips it should travel across."

private const val CLIP_ONLY_LOOPING =
  "This effect reads where a frame sits inside the run it is drawn over, and a looping track " +
    "lays its clips down more than once, so there is no single run to measure against. Take " +
    "the loop off the track, or take the effect off the clip."
