package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.tracksWithMediaType

/**
 * The audio track of this asset that [clip] plays, found by its place among the asset's audio tracks.
 *
 * The planner chose it out of the tracks the probe listed, which are this asset's audio tracks in the same order, so
 * its place finds it whatever the container numbers its tracks by. Null when the clip plays no audio track, so a
 * source without audio lays empty time as before. Every other audio track of the asset is left out.
 */
internal fun AVAsset.audioTrackOf(clip: ResolvedClip): AVAssetTrack? {
  val chosen = clip.audioTrack ?: return null
  return tracksWithMediaType(AVMediaTypeAudio)
    .filterIsInstance<AVAssetTrack>()
    .getOrNull(chosen.ordinal)
}
