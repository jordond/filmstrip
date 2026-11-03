package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import kotlin.time.Duration

/**
 * Where playback inside a loop range wraps, on the output frame grid of the composition it loops in.
 *
 * @property at The playhead reading that wraps. That is the time of the slot after [lastSlot], or the composition's
 * duration when the range [reachesEnd], so playback never reaches a slot the range does not play.
 * @property firstSlot The output slot the range starts on, which is where a wrap lands.
 * @property lastSlot The last output slot the range plays before it wraps.
 * @property reachesEnd Whether the range plays the composition's last slot. When it does the composition ends inside
 * the range, and playback that reaches the composition's end wraps rather than ending.
 */
internal class LoopWrap(
  val at: Duration,
  val firstSlot: Long,
  val lastSlot: Long,
  val reachesEnd: Boolean,
)

/**
 * Where a loop over [range] wraps in a composition [duration] long at [frameRate], or null when the range has nothing
 * of the composition to loop.
 *
 * The range's end is held to [duration], so a range with no end and one ending past the duration both stop where the
 * composition does. Its slots run from the one its start lands on up to, and not including, the one its held end lands
 * on, the way [dev.jordond.filmstrip.edit.outputSlotsOf] counts them. A range too narrow to cover a slot of its own
 * loops on the slot its start lands on.
 *
 * Playback wraps where the slot after the range's last one starts. For a range ending inside a slot that is before its
 * own end when the end rounds down and after it when the end rounds up. A range that plays the composition's last slot
 * wraps at [duration] instead.
 *
 * A range whose start lands past the composition's last slot, which every range starting at or past [duration] does,
 * has nothing to loop and gives null.
 *
 * @throws IllegalArgumentException when [frameRate] is zero or negative.
 */
internal fun loopWrapOf(
  range: TimeRange,
  duration: Duration,
  frameRate: Int,
): LoopWrap? {
  val compositionLast = outputSlotOf(duration, frameRate) - 1
  val first = outputSlotOf(range.start, frameRate)
  if (first > compositionLast) return null

  val end = range.endExclusive?.coerceAtMost(duration) ?: duration
  val last = (outputSlotOf(end, frameRate) - 1).coerceIn(first, compositionLast)
  val reachesEnd = last == compositionLast
  return LoopWrap(
    at = if (reachesEnd) duration else outputSlotTime(last + 1, frameRate),
    firstSlot = first,
    lastSlot = last,
    reachesEnd = reachesEnd,
  )
}
