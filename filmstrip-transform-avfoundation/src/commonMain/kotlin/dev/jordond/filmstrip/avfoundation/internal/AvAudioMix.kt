package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.time.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.timeRange
import kotlin.time.Duration

/**
 * The mix that applies each clip's gain, or null when every clip is at full volume.
 *
 * AVFoundation expresses a linear ramp natively and exactly, so a clip's curve is written straight
 * across as one call per [GainSegment].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun List<Pair<AVMutableCompositionTrack, List<Placement>>>.toAudioMix(): AVMutableAudioMix? {
  if (all { (_, placements) -> placements.all { it.clip.gain.constant == 1f } }) return null

  val parameters =
    map { (media, placements) ->
      AVMutableAudioMixInputParameters().apply {
        setTrackID(media.trackID)
        placements.forEach { it.writeGain(this) }
      }
    }

  return AVMutableAudioMix().apply { setInputParameters(parameters) }
}

/**
 * Writes this placement's gain curve onto [parameters], in the composition time [parameters]
 * already reads.
 *
 * A constant curve is one `setVolumeRampFromStartVolume:toEndVolume:timeRange:` holding that level
 * at both ends of the placement's own span, so the level stays where it was written until the next
 * placement writes its own. A ramping curve is one ramp per [GainSegment], with the segment's
 * clip-relative `start`/`end` shifted onto the timeline by this placement's own [Placement.start],
 * since a curve is measured from zero at the clip's own start and AVFoundation only understands the
 * composition's time. A zero-length segment is a step rather than a ramp, and AVFoundation has no
 * ramp to hold across no time, so it is written as a plain `setVolume:atTime:` at the instant the
 * step lands on.
 */
@OptIn(ExperimentalForeignApi::class)
private fun Placement.writeGain(parameters: AVMutableAudioMixInputParameters) {
  val constant = clip.gain.constant
  if (constant != null) {
    parameters.setVolumeRampFromStartVolume(
      constant,
      toEndVolume = constant,
      timeRange = timeRangeOf(start, end - start),
    )
    return
  }

  clip.gain.segments.forEach { segment ->
    val segmentStart = start + segment.start
    val length = segment.end - segment.start
    if (length <= Duration.ZERO) {
      parameters.setVolume(segment.endGain, atTime = segmentStart.toCMTime())
    } else {
      parameters.setVolumeRampFromStartVolume(
        segment.startGain,
        toEndVolume = segment.endGain,
        timeRange = timeRangeOf(segmentStart, length),
      )
    }
  }
}
