package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A composition whose audio is [clip]'s alone.
 *
 * A clip that covers an output frame at [frameRate] is the whole composition. One sped up far enough to cover none
 * would be refused as a primary, so it is heard on an audio track instead, under the first frame of [picture] on a
 * video track.
 */
internal fun compositionHearing(
  clip: Clip,
  picture: MediaSource,
  frameRate: Int = DEFAULT_FRAME_RATE,
): Composition {
  val frame = 1.seconds / frameRate
  if (checkNotNull(clip.duration) >= frame) return Composition(listOf(Track(listOf(clip))))
  val still = Clip(picture, TimeRange.of(Duration.ZERO, frame))
  return Composition(
    listOf(Track(listOf(still), content = TrackContent.Video), Track(listOf(clip), content = TrackContent.Audio)),
  )
}

private const val DEFAULT_FRAME_RATE = 30
