package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.WINDOW_CLOCK
import dev.jordond.filmstrip.transform.internal.NEAREST_FRAME_TIE
import dev.jordond.filmstrip.transform.internal.holdsNoFrame
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.sourceRateOr
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How one clip's picture is read off its input before any effect runs.
 *
 * @property nodes The trim and the clock the clip's frames leave on.
 * @property clockOrigin Where the clock's zero lands in the composition, which an effect reading the composition's time
 * counts from.
 */
internal class ClipRead(
  val nodes: List<FilterNode>,
  val clockOrigin: Duration,
)

/**
 * How this clip's picture is read at [frameRate].
 *
 * The frame nearest a slot's source time can sit outside the trim, so a retimed read opens on the frame its first slot
 * shows and runs a frame past the trim's end, and the retimed clock and the tail's cut decide what shows. A retimed
 * clock opens on the clip's first slot, so that slot's time is its origin. A plain read restarts on the first frame the
 * trim holds.
 */
internal fun ResolvedClip.readAt(frameRate: Int): ClipRead {
  if (!retimes(frameRate)) {
    val trim = FilterNode("trim", "start" to formatSeconds(start.seconds()), "end" to formatSeconds(end.seconds()))
    return ClipRead(listOf(trim, RESTART_PTS), span.start)
  }
  val sourceRate = sourceRateOr(frameRate)
  val opens = formatSeconds(retimedReadOpens(frameRate).seconds())
  val trim = FilterNode("trim", "start" to opens, "end" to formatSeconds((end + 1.seconds / sourceRate).seconds()))
  val lead = spanLead(frameRate)
  return ClipRead(listOf(trim) + retimedNodes(start, speed, frameRate, sourceRate, lead), span.start - lead)
}

/**
 * Whether this clip's frames are moved onto its slots at [frameRate] by their source time.
 *
 * A source at another rate leaves `fps` to keep the frame its own rounding picks, a trim holding no frame has none to
 * restart the clock on, and a clock restarted on the first frame a trim holds shows the wrong frame when the nearest is
 * the one before it or the span opens far enough off its first slot to land a slot on the next one.
 */
internal fun ResolvedClip.retimes(frameRate: Int): Boolean =
  speed != 1.0 ||
    sourceRateOr(frameRate) != frameRate.toDouble() ||
    holdsNoFrame(frameRate) ||
    !restartShowsTheNearestFrames(frameRate)

/**
 * Moves a clip's frames onto the output slots [speed] plays them at, counted from [trimStart] whichever frame the trim
 * opens on, so a slot shows the same source frame from any window.
 *
 * The clock runs in ticks a slot divides into exactly, so a window's shift is a whole number of slots and a frame
 * halfway between two slots rounds the same way from every window. `fps` then writes one frame per slot from the
 * window's opening, dropping what the seek delivered ahead of it and holding the frame showing when it opens. The slot
 * count the tail cuts to is what holds the clip's length, so nothing here decides how long it runs.
 *
 * The clip's first slot is the clock's zero, and [trimStart] plays where the span opens, [spanLead] past that slot's
 * time, so every slot reads the source time `sourceTimeOfSlot` gives it.
 *
 * Each frame then moves onto the nearest whole microsecond, the time base a live input is stamped on, so an effect
 * reading the time and its live form read the same one.
 *
 * `fps` keeps the last frame that rounds onto a slot, so the clock is moved until that frame is the one nearest the
 * slot's source time. That takes half a slot less half a source frame at [speed], which is negative once a source frame
 * outlasts a slot, and the frames that moves the end of the clip back by are held at its end.
 *
 * Every frame's time is read [NEAREST_FRAME_TIE] late. Where a slot's source time sits within that of half way between
 * two frames, the later one then rounds onto the next slot and the slot keeps the earlier one, the frame
 * [nearestSourceFrame] picks at a tie. That holds to this clock's tick.
 *
 * @param sourceRate The frame rate of the source being read.
 * @param spanLead How far past its first slot's time the clip's span opens, negative when it opens ahead of it.
 */
internal fun retimedNodes(
  trimStart: Duration,
  speed: Double,
  frameRate: Int,
  sourceRate: Double,
  spanLead: Duration,
): List<FilterNode> {
  val nearest = (TICKS_PER_SLOT - TICKS_PER_SLOT * frameRate / (sourceRate * speed)) / 2
  val shift = (nearest + spanLead.seconds() * frameRate * TICKS_PER_SLOT).roundToLong()
  val opens = formatSeconds(trimStart.seconds())
  val tie = formatSeconds(NEAREST_FRAME_TIE.seconds())
  val clock = "trunc((PTS-$opens/TB+$tie/TB)/$speed)+($shift)-round($WINDOW_CLOCK/TB)"
  return buildList {
    add(FilterNode("settb", "expr" to "1/${frameRate * TICKS_PER_SLOT}"))
    add(FilterNode("setpts", "expr" to clock))
    add(FilterNode("fps", "fps" to frameRate.toString(), "start_time" to "0"))
    if (shift < 0) add(FilterNode("tpad", "stop_mode" to "clone", "stop" to (-shift / TICKS_PER_SLOT + 1).toString()))
    add(FilterNode("settb", "expr" to "1/$LIVE_STAMP_BASE"))
  }
}

private const val TICKS_PER_SLOT = 1_000

/**
 * How far past its first slot's time this clip's span opens at [frameRate], negative when it opens ahead of it.
 */
internal fun ResolvedClip.spanLead(frameRate: Int): Duration =
  span.start - outputSlotTime(outputSlotOf(span.start, frameRate), frameRate)

/**
 * Whether a clock restarted on the first frame this clip's trim holds shows on each slot the frame [nearestSourceFrame]
 * picks for it.
 *
 * At speed 1 on a source at the output's rate each slot past the first steps one frame on, so the first two slots
 * decide it. Reads the source's frame grid from zero the way `holdsNoFrame` does, with each frame on its whole
 * microsecond, and [outputRate] stands in for a source that names no rate.
 */
internal fun ResolvedClip.restartShowsTheNearestFrames(outputRate: Int): Boolean {
  val slots = outputSlotsOf(span, outputRate)
  if (slots.isEmpty()) return true
  val rate = sourceRateOr(outputRate)
  val opening = ceil(start.seconds() * rate).toLong()
  return (slots.first..minOf(slots.first + 1, slots.last)).all { slot ->
    gridFrameNearest(sourceTimeOfSlot(start, span.start, speed, slot, outputRate), rate) ==
      opening + slot - slots.first
  }
}

/**
 * Where a retimed read of this clip opens, half a source frame ahead of the frame its first slot shows, so no frame
 * before that one reaches the clock.
 *
 * Reads the source's frame grid the way [restartShowsTheNearestFrames] does.
 */
internal fun ResolvedClip.retimedReadOpens(outputRate: Int): Duration {
  val rate = sourceRateOr(outputRate)
  val first = outputSlotOf(span.start, outputRate)
  val shown = gridFrameNearest(sourceTimeOfSlot(start, span.start, speed, first, outputRate), rate)
  return (gridFrameTime(shown, rate) - 1.seconds / rate / 2).coerceAtLeast(Duration.ZERO)
}

private fun gridFrameNearest(
  target: Duration,
  rate: Double,
): Long {
  val index = floor(target.inWholeMicroseconds * rate / MICROS_PER_SECOND).toLong()
  val frames = listOf(index, index + 1).map { gridFrameTime(it, rate) }
  return index + checkNotNull(nearestSourceFrame(frames, target))
}

private fun gridFrameTime(
  index: Long,
  rate: Double,
): Duration = (index * MICROS_PER_SECOND / rate).toLong().microseconds

private const val MICROS_PER_SECOND = 1_000_000.0
