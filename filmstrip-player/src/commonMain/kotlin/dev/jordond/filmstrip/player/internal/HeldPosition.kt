package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlin.time.Duration

/**
 * Where the playhead holds once a composition [duration] long at [frameRate] replaces the one it was reading [current]
 * in, for a load that asked for no position of its own.
 *
 * A paused playhead lands on the output slot it rounds to, the way an exact seek lands. While [playing] it lands on the
 * first slot at or after its time, compared in whole microseconds, which is the precision a slot time carries. Either
 * way a playhead past the end of a shorter composition holds on its last frame, so a playing one inside the last slot
 * steps back to that slot's start, by less than one slot.
 *
 * While [playing] with [loopRange] standing, a playhead at the range's wrap or landing on a slot past its last one
 * steps back to the range's first slot instead, which is where playback reaching the wrap goes. A range the composition
 * ends inside wraps at its end, the way [loopWrapOf] places it, so a playing playhead past a shorter composition's end
 * lands on the range's start. A playhead before the range keeps going from its own slot, since playback runs on into
 * the range. A range only wraps while playback runs, so a paused playhead lands where an exact seek would, past the
 * range or not.
 */
internal fun heldPosition(
  current: Duration,
  duration: Duration,
  frameRate: Int,
  loopRange: TimeRange?,
  playing: Boolean,
): Duration {
  val nearest = seekLandingOf(current, duration, frameRate)
  if (!playing) return nearest
  val landing =
    if (nearest.inWholeMicroseconds < current.inWholeMicroseconds) {
      seekLandingOf(outputSlotTime(outputSlotOf(current, frameRate) + 1, frameRate), duration, frameRate)
    } else {
      nearest
    }
  val wrap = loopRange?.let { loopWrapOf(it, duration, frameRate) } ?: return landing
  val pastWrap = current >= wrap.at || outputSlotOf(landing, frameRate) > wrap.lastSlot
  return if (pastWrap) outputSlotTime(wrap.firstSlot, frameRate) else landing
}

/**
 * Where the playhead holds once this composition replaces the one it was reading [current] in, as [heldPosition] places
 * it.
 */
internal fun ResolvedComposition.heldPosition(
  current: Duration,
  loopRange: TimeRange?,
  playing: Boolean,
): Duration = heldPosition(current, duration, frameRate, loopRange, playing)
