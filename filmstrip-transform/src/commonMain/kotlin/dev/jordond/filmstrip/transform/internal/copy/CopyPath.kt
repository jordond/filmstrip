package dev.jordond.filmstrip.transform.internal.copy

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.media.clipAudioTrackOf
import dev.jordond.filmstrip.media.videoCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlin.time.Duration

/**
 * What the engine says about copying a stream across without an encoder.
 *
 * @property supportsCopy Whether the engine can copy a stream at all.
 * @property muxerAccepts Whether its muxer takes a source's streams as they are. It is handed the source as a copy
 * writes it, with only the audio track the clip plays.
 * @property copyLimit The blocker a limit of the engine's own puts on copying a source, or null when it has none.
 */
internal class CopySupport(
  val supportsCopy: Boolean,
  val muxerAccepts: (MediaInfo) -> Boolean,
  val copyLimit: (MediaInfo) -> CopyBlocker? = { null },
)

/**
 * Every reason this export cannot copy its streams across, HDR set aside.
 *
 * HDR is left out because whether a copy can carry the grade is itself one of the
 * things that decides HDR, so this has to be answerable before that decision is made.
 *
 * [heightPx] is the height the spec moves the frame to, null when it asks for none or for one the frame already
 * reaches, so a height at or above the source's own keeps the copy. A [VideoCodec.MatchSource] request names the
 * codec a copy already writes, so it keeps the copy too.
 *
 * [copyOpening] is where the copy would open, and null is a cut no copy can reach. [requestedSize]
 * is the frame the composition asks for, before any encoder ceiling is applied, since a copy runs
 * no encoder and so has no ceiling to be held to. Comparing it against [sourceSize] is what says
 * the geometry is identity.
 *
 * [copyPackets] are the clip's video packets over a copy window that ends before its source does, and a listing that
 * [lacksPresentationTimes] blocks the copy, since it gives the copy no frame to stop on.
 *
 * Each scope's level reads the same way. A level that leaves a stream out blocks the copy as a
 * stream change, a level that scales the samples blocks it as a gain change, and a volume of one
 * blocks nothing.
 */
internal fun copyBlockers(
  support: CopySupport,
  edit: Composition,
  spec: ExportSpec,
  heightPx: Int?,
  sourceSize: Size,
  requestedSize: Size,
  copyOpening: Duration?,
  info: MediaInfo,
  copyPackets: List<CopyPacket>? = null,
): List<CopyBlocker> =
  buildList {
    val copied = copiedInfo(edit, info)
    if (!support.supportsCopy) add(CopyBlocker.BackendCannotCopy)
    if (!support.muxerAccepts(copied)) add(CopyBlocker.MuxerRefusesSource)
    support.copyLimit(copied)?.let(::add)
    if (!codecsAreNameable(copied)) add(CopyBlocker.SourceCodecUnnameable)
    if (spec.frameRate != null) add(CopyBlocker.FrameRateSet)
    if (spec.videoCodec != VideoCodec.Auto && spec.videoCodec != VideoCodec.MatchSource) {
      add(CopyBlocker.VideoCodecNamed)
    }
    if (spec.audioCodec != AudioCodec.Auto) add(CopyBlocker.AudioCodecNamed)
    if (heightPx != null) add(CopyBlocker.HeightSet)
    if (spec.bitrate != null || spec.quality != null) add(CopyBlocker.BitrateSet)
    if (sourceSize != requestedSize) add(CopyBlocker.FrameResized)
    if (edit.effects.isNotEmpty()) add(CopyBlocker.CompositionHasEffects)
    if (edit.audio.dropsAStream) add(CopyBlocker.AudioSpecChanged)
    if (edit.audio.scalesSamples) add(CopyBlocker.CompositionGainChanged)

    val track = edit.tracks.singleOrNull()
    if (track == null) {
      add(CopyBlocker.MultipleTracks)
      return@buildList
    }
    if (track.effects.isNotEmpty()) add(CopyBlocker.TrackHasEffects)
    if (track.start > Duration.ZERO) add(CopyBlocker.TrackStartsLate)
    if (track.content != TrackContent.AudioAndVideo) add(CopyBlocker.TrackDropsAStream)
    if (track.altersGain) add(CopyBlocker.TrackGainChanged)

    val clip = track.clips.singleOrNull()
    if (clip == null) {
      add(CopyBlocker.MultipleClips)
      return@buildList
    }
    if (clip.effects.isNotEmpty()) add(CopyBlocker.ClipHasEffects)
    if (clip.altersGain) add(CopyBlocker.ClipGainChanged)
    if (clip.speed != 1.0 && clip.source !is MediaSource.Image) add(CopyBlocker.ClipSpeedChanged)
    if (copyOpening == null) add(CopyBlocker.TrimNotOnSyncSample)
    if (copyPackets?.lacksPresentationTimes() == true) add(CopyBlocker.SourceUntimed)
  }

/**
 * Whether the platform can copy the streams across without re-encoding them.
 *
 * Deliberately conservative: [copyKeepsHdr] already answers everything about the shape of the
 * composition, so the one thing left to add is whether HDR actually resolved to being kept rather
 * than tone-mapped. Which path actually ran is reported by the backend once the file is written.
 */
internal fun exportPath(
  copyKeepsHdr: Boolean,
  hdr: ResolvedHdr,
): ExportPath = if (copyKeepsHdr && hdr == ResolvedHdr.Keep) ExportPath.Copy else ExportPath.Transcode

/**
 * Reads where a stream copy of [composition] could open, keyed the way
 * [dev.jordond.filmstrip.transform.internal.ExportPlanner.negotiate] reads it.
 *
 * Only a single clip on a single track can ever be copied, and only a cut past zero has a sync
 * sample to look for, so at most one [probe] runs. It runs whatever else the spec asks for, because
 * a plan that skipped the probe could not tell a caller whether the trim was the term that cost
 * them the copy.
 */
@InternalFilmstripApi
public suspend fun copyOpenings(
  composition: Composition,
  probe: suspend (MediaSource, Duration) -> Duration?,
): Map<MediaSource, Duration> {
  val clip =
    composition.tracks
      .singleOrNull()
      ?.clips
      ?.singleOrNull() ?: return emptyMap()
  if (clip.source is MediaSource.Image) return emptyMap()
  val cut = clip.trim?.start?.takeIf { it > Duration.ZERO } ?: return emptyMap()
  return probe(clip.source, cut)?.let { mapOf(clip.source to it) } ?: emptyMap()
}

/**
 * Where a stream copy of [clip] would open, or null when no copy can reach its cut.
 *
 * An untrimmed clip opens at zero, which every stream starts on. A trimmed one opens on [opening],
 * the sync sample at or before its cut, and only while that sits no further back than
 * [Clip.snapWithin] allows. A cut already on a sync sample is reached at any tolerance, zero
 * included, because opening there costs no accuracy.
 */
internal fun copyOpening(
  clip: Clip,
  opening: Duration?,
): Duration? {
  val asked = clip.trim?.start ?: return Duration.ZERO
  if (asked == Duration.ZERO) return Duration.ZERO
  val sync = opening ?: return null
  if (sync > asked) return null
  return sync.takeIf { asked - it <= clip.snapWithin }
}

/**
 * [info] as a copy of [edit] writes it, with the one audio track its clip plays and none of the source's others.
 *
 * The muxer and the codec checks read this, so a source whose default track a muxer refuses still copies a chosen
 * track it takes, and the other way round. An edit of more than one clip never copies, and gets [info] unchanged.
 */
private fun copiedInfo(
  edit: Composition,
  info: MediaInfo,
): MediaInfo {
  val clip =
    edit.tracks
      .singleOrNull()
      ?.clips
      ?.singleOrNull() ?: return info
  return MediaInfo(info.duration, info.video, listOfNotNull(clipAudioTrackOf(clip, info)), info.isExportable)
}

/**
 * Whether [info]'s streams could report codecs the public enums can name.
 *
 * A muxer can agree to copy bytes neither [VideoCodec] nor [AudioCodec] has a member for, which
 * `muxerAccepts` alone does not catch, so this is checked alongside it.
 */
private fun codecsAreNameable(info: MediaInfo): Boolean {
  val video = info.video
  val audio = info.audio
  return (video == null || videoCodecOf(video.codec.kind) != null) &&
    (audio == null || audioCodecOf(audio.codec.kind) != null)
}

// Whether this level leaves one of the source's streams out of the file. Mute keeps the track and
// writes silence into it, so like a volume it only scales the samples.
private val AudioSpec.dropsAStream: Boolean
  get() =
    when (this) {
      is AudioSpec.Remove, is AudioSpec.AudioOnly -> true
      is AudioSpec.Keep, is AudioSpec.Mute, is AudioSpec.Volume -> false
    }

// Whether this level scales the samples of a stream it keeps, read the way AudioLevel.isUnity reads
// a track's or a clip's level.
private val AudioSpec.scalesSamples: Boolean
  get() =
    when (this) {
      is AudioSpec.Mute -> true
      is AudioSpec.Volume -> gain != 1f
      is AudioSpec.Keep, is AudioSpec.Remove, is AudioSpec.AudioOnly -> false
    }

/**
 * Whether this level leaves the audio exactly as it was, which is what lets a copy skip the mixer.
 *
 * An envelope is never unity here, even one written flat at one, since reading it would mean
 * folding the curve the copy path does not build.
 */
private val AudioLevel.isUnity: Boolean
  get() =
    when (this) {
      is AudioLevel.Inherit -> true
      is AudioLevel.Volume -> gain == 1f
      is AudioLevel.Mute, is AudioLevel.Envelope -> false
    }

// A fade is carried apart from the level, so a copy has to be blocked by either of them.
private val Track.altersGain: Boolean
  get() = !audio.isUnity || fadeIn > Duration.ZERO || fadeOut > Duration.ZERO

private val Clip.altersGain: Boolean
  get() = !audio.isUnity || fadeIn > Duration.ZERO || fadeOut > Duration.ZERO
