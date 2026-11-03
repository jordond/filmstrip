package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxTimeline
import dev.jordond.filmstrip.effect.FilterNode
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

// The gate and the opacity, which is everything a branch does to its picture's alpha.
internal fun PlacedPicture.alphaNodes(
  drawnRange: TimeRange,
  span: TimeRange,
  rate: Int?,
  instance: String,
  driven: Boolean,
): List<FilterNode> =
  buildList {
    // After the format, which is the node that guarantees the alpha channel the gate zeroes.
    windowGate(drawnRange, span, rate)?.let { add(it) }
    when {
      // The sampler has already folded the authored opacity into every value it answers, so the
      // node the commands drive opens at one rather than at what was authored.
      driven -> add(FilterNode("colorchannelmixer$instance", "aa" to "1"))
      opacity < 1f -> add(FilterNode("colorchannelmixer", "aa" to opacity.toString()))
    }
  }

// The window, as a node on the overlay's own branch: outside the drawn range the branch is handed on
// fully transparent, so the merge blends nothing onto the frame under it, and inside it the node
// is switched off and passes the picture through untouched.
//
// t is the image branch's own clock, read ahead of the setpts that rebases it: an export opens
// that branch at zero and a scrubbed preview carries it to the seek with -itsoffset, so either
// way it reads the composition time the frame belongs to. It counts from the slot the span opens
// on, which is where the branch's first frame sits.
//
// The bound is half open, the way TimeRange and frameWithin read it, so the frame sitting on the
// drawn range's end is outside it, as it is on every other backend.
//
// The branch advances on the output frame grid, so the frames this opens over are the ones the
// shared sampler names. A clip's merge runs ahead of the tail's fps, and that resampler builds
// two output frames out of one source frame either side of a boundary, so on a clip whose own
// rate differs from the output's an edge lands within one output frame of the sampler's. That is
// the resampler's limit rather than the window's.
private fun PlacedPicture.windowGate(
  drawnRange: TimeRange,
  span: TimeRange,
  rate: Int?,
): FilterNode? {
  if (effect.visibleDuring == null) return null
  val end = drawnRange.endExclusive ?: return null

  val open = "gte(t,${branchSeconds(drawnRange.start, span, rate)})*lt(t,${branchSeconds(end, span, rate)})"
  return FilterNode("colorchannelmixer", "aa" to "0", "enable" to "not($open)")
}

// An image input is one frame at t = 0, and a branch carrying one frame ends on it. The overlay
// is drawn for as long as the branch it merges onto, so the still is repeated across that whole
// drawn range. A pipeline that resolved neither a rate nor a bounded span has nothing to repeat it over.
internal fun Attributes.auxTimeline(): AuxTimeline? {
  val rate = frameRate?.takeIf { it > 0f } ?: return null
  val length = span.duration ?: return null

  return AuxTimeline(rate, length)
}

// The output rate the planner resolved, which it carries as a Float holding a whole number.
internal val Attributes.gridRate: Int?
  get() = frameRate?.takeIf { it > 0f }?.roundToInt()

// The first output frame at or after a composition time, counted on the branch's own clock. The
// branch's frames sit on the output slots from the one the span's start lands on, so this counts
// the slots from there to the first one at or after [time]. The comparison is in whole
// microseconds, the grid's own unit, so a time sitting on a slot counts as that slot.
internal fun frameIndexAt(
  time: Duration,
  span: TimeRange,
  rate: Int,
): Int {
  val landed = outputSlotOf(time, rate)
  val atOrAfter = if (outputSlotTime(landed, rate) < time.inWholeMicroseconds.microseconds) landed + 1 else landed
  return (atOrAfter - outputSlotOf(span.start, rate)).coerceAtLeast(0).toInt()
}

// Where a composition time lands on the branch the effect runs on, which is the clock every time
// expression on that branch is read against. With a rate, the bound sits half a frame ahead of the
// first frame at or after [time], so that frame's own time is clear of it however either side
// rounds its division. With none, the time is moved onto the branch as it stands.
private fun branchSeconds(
  time: Duration,
  span: TimeRange,
  rate: Int?,
): String =
  if (rate == null) {
    (time - span.start).toDouble(DurationUnit.SECONDS).toString()
  } else {
    ((frameIndexAt(time, span, rate) - HALF_FRAME) / rate).toString()
  }

// Half a frame, in frames, which is how far ahead of a frame a window bound is written.
private const val HALF_FRAME = 0.5
