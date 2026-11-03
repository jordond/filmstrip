package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration

// How long the picture holds under an audio track that outlasts it, derived once. Every backend writes the video
// track to the composition's end on the primary's last frame, and reads the slots and the frame count from here.

/**
 * The output slots the primary's last frame is held over at [frameRate].
 *
 * Runs from the slot the primary's last laid pass ends on up to the slot the composition ends on, so it opens right
 * after the last slot the primary covers and closes on [videoSlotsOf]. Empty when nothing outlasts the primary, which
 * includes a looping primary, since its run already ends with the composition.
 *
 * @param primary The composition's first track, as the planner laid it.
 * @param duration How long the whole composition runs.
 */
@InternalFilmstripApi
public fun heldTailOf(
  primary: ResolvedTrack,
  duration: Duration,
  frameRate: Int,
): LongRange {
  val lastPass = primary.clips.last()
  val primaryEnd = checkNotNull(lastPass.span.endExclusive)
  return outputSlotOf(primaryEnd, frameRate) until videoSlotsOf(duration, frameRate)
}

/**
 * How many frames the video track writes at [frameRate], one for every output slot a composition of [duration]
 * covers.
 *
 * The primary's lead, the slots its passes cover and [heldTailOf] fill exactly this many, so the count is the same
 * whether or not anything outlasts the primary.
 */
@InternalFilmstripApi
public fun videoSlotsOf(
  duration: Duration,
  frameRate: Int,
): Long = outputSlotOf(duration, frameRate)

/**
 * The output slots this composition holds its primary's last frame over, from [heldTailOf].
 *
 * Empty when nothing outlasts the primary.
 */
@InternalFilmstripApi
public val ResolvedComposition.heldTail: LongRange
  get() = heldTailOf(tracks.first(), duration, output.frameRate)

/**
 * How many frames this composition's video track writes, from [videoSlotsOf].
 *
 * The last slot of a non-empty [heldTail] is the last of these.
 */
@InternalFilmstripApi
public val ResolvedComposition.videoSlots: Long
  get() = videoSlotsOf(duration, output.frameRate)
