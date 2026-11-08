package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.interop.SampleTimes
import dev.jordond.filmstrip.avfoundation.internal.interop.sampleTimesAround
import dev.jordond.filmstrip.avfoundation.internal.interop.tickTime
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.ticks
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMutableCompositionTrack
import platform.CoreMedia.CMTimeRangeMake
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lays [clip] onto this track over [slots] of the grid at [grid], each slot showing the source frame
 * [nearestSourceFrame] picks for its [sourceTimeOfSlot]. A slot past the clip's own, on a held tail, shows what its
 * last one does.
 *
 * Slots in a row that one read shows frame by frame take one segment, read from a source start shifted so every slot
 * of the row lands inside its own frame and scaled to the clip's speed. Each slot is checked against its frame, from
 * the tick before its own time to the tick after it. A row that holds one frame and no more takes a single tick read
 * stretched over it instead. A clip from a source at a constant rate and a constant speed is one segment.
 *
 * A frame read from the same tick as the frame after it is one no read can show, since AVFoundation shows the later of
 * the two there. A slot whose nearest frame is one of those holds the last frame read from that tick instead, which
 * sits under a tick from it.
 *
 * When a segment is refused, whatever this clip already laid is taken back off the track before the failure is
 * thrown, so the track holds all of the clip or none of it.
 *
 * @param samplesOf Reads the source's sample times from the first time to the second.
 * @throws AvLoweringFailure When the source's sample times can't be read, there are more of them than one clip may
 * read, or AVFoundation refuses a segment.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun AVMutableCompositionTrack.insertShown(
  source: AVAssetTrack,
  clip: ResolvedClip,
  slots: LongRange,
  grid: Int,
  samplesOf: suspend (
    from: Duration,
    to: Duration,
  ) -> SampleTimes? = { from, to -> source.sampleTimesAround(from, to) },
) {
  val covered = outputSlotsOf(clip.span, grid)
  val targets = covered.map { sourceTimeOfSlot(clip.start, clip.span.start, clip.speed, it, grid) }
  val samples = samplesOf(targets.first(), targets.last()) ?: throw AvLoweringFailure(unreadTimes(clip))
  val count = (slots.last - slots.first + 1).toInt()
  // AVFoundation's one timescale limit: frames inside one tick of it can't each be read, so a slot whose nearest is
  // one of those shows the last of them, under a tick from its own.
  val shown =
    IntArray(count) {
      samples.shownOf(checkNotNull(nearestSourceFrame(samples.times, targets[minOf(it, targets.lastIndex)])))
    }
  val row =
    ShownRow(
      samples = samples,
      speed = clip.speed,
      shown = shown,
      slotTicks = LongArray(count + 1) { outputSlotTime(slots.first + it, grid).ticks() },
      firstSlot = slots.first,
      grid = grid,
    )

  var index = 0
  while (index < count) {
    val opens = row.slotTicks[index]
    var held = index + 1
    while (held < count && shown[held] == shown[index]) held++
    val played = row.playedFrom(index)?.takeIf { it.closes > held }
    val closes = played?.closes ?: held
    val length = row.slotTicks[closes] - opens
    val inserted =
      if (played != null) {
        insertPlayed(source, opens, length, played)
      } else {
        insertHeld(source, opens, length, samples.heldTickOf(shown[index]))
      }
    if (!inserted) {
      val laid = opens - row.slotTicks[0]
      if (laid > 0) removeTimeRange(CMTimeRangeMake(start = tickTime(row.slotTicks[0]), duration = tickTime(laid)))
      throw AvLoweringFailure(refusedSegment(clip, opens))
    }
    index = closes
  }
}

private fun unreadTimes(clip: ResolvedClip): String =
  "AVFoundation could not read the frame times of ${clip.source.describe()}, so no output frame can be given the " +
    "source frame nearest it."

private fun refusedSegment(
  clip: ResolvedClip,
  opens: Long,
): String {
  val at = (opens.toDouble() / MEDIA_TIMESCALE).seconds
  return "AVFoundation refused to lay ${clip.source.describe()} on the video track at $at."
}

/**
 * A row of slots read from [read] for [sourceTicks], running up to slot [closes].
 */
private class PlayedRow(
  val closes: Int,
  val read: Long,
  val sourceTicks: Long,
)

/**
 * The frame each of a clip's slots shows, [shown] indexing [samples], with the tick each slot's segment opens on in
 * [slotTicks] and one more for where the last closes.
 */
private class ShownRow(
  val samples: SampleTimes,
  val speed: Double,
  val shown: IntArray,
  val slotTicks: LongArray,
  private val firstSlot: Long,
  private val grid: Int,
) {
  private val lowest = DoubleArray(shown.size)
  private val highest = DoubleArray(shown.size)

  /**
   * The longest row from slot [index] that one read at [speed] shows frame by frame, or null when the source has no
   * tick to read it from.
   *
   * Every slot narrows the source ticks the read may open on to those landing the slot inside its frame, and the row
   * ends where none is left. Those bounds take the speed as given, while the segment reads a whole number of ticks
   * that can land up to half a tick from it. So the row is then checked against the ticks its segment reads, and when
   * it fails the longest shorter row that holds is searched for by halving.
   */
  fun playedFrom(index: Int): PlayedRow? {
    val opens = slotTicks[index]
    var low = 0.0
    var high = Double.MAX_VALUE
    var closes = index
    while (closes < shown.size) {
      val frame = shown[closes]
      val nextLow = maxOf(low, samples.readTickOf(frame) - speed * earliestOf(closes, opens))
      val nextHigh =
        minOf(
          high,
          samples.lastTickOf(frame) - speed * latestOf(closes, opens),
          (samples.endTick - sourceTicksOf(slotTicks[closes + 1] - opens)).toDouble(),
        )
      if (ceil(nextLow) > floor(nextHigh)) break
      low = nextLow
      high = nextHigh
      lowest[closes] = low
      highest[closes] = high
      closes++
    }
    if (closes == index) return null
    rowUntil(index, closes)?.let { return it }
    var failing = closes
    var found: PlayedRow? = null
    var holding = index
    while (failing - holding > 1) {
      val middle = (holding + failing) / 2
      val row = rowUntil(index, middle)
      if (row != null) {
        found = row
        holding = middle
      } else {
        failing = middle
      }
    }
    return found
  }

  // The row over slots index until closes, read from a tick its bounds allow, or null when the ticks it reads don't
  // land every slot in its own frame.
  private fun rowUntil(
    index: Int,
    closes: Int,
  ): PlayedRow? {
    val first = ceil(lowest[closes - 1]).toLong()
    val last = floor(highest[closes - 1]).toLong()
    val own = samples.readTickOf(shown[index])
    val read = if (own in first..last) own else (first + last) / 2
    val sourceTicks = sourceTicksOf(slotTicks[closes] - slotTicks[index])
    return if (holds(index, closes, read, sourceTicks)) PlayedRow(closes, read, sourceTicks) else null
  }

  // Whether a read from read for sourceTicks, laid over slots index until closes, lands each of them in its own frame.
  private fun holds(
    index: Int,
    closes: Int,
    read: Long,
    sourceTicks: Long,
  ): Boolean {
    val opens = slotTicks[index]
    val length = slotTicks[closes] - opens
    if (read < 0 || read + sourceTicks > samples.endTick) return false
    return (index until closes).all { slot ->
      val frame = shown[slot]
      read * length + earliestOf(slot, opens) * sourceTicks >= samples.readTickOf(frame) * length &&
        read * length + latestOf(slot, opens) * sourceTicks <= samples.lastTickOf(frame) * length
    }
  }

  // The whole ticks of source a segment laid over length ticks reads at speed.
  private fun sourceTicksOf(length: Long): Long =
    sourceLengthOf((length.toDouble() / MEDIA_TIMESCALE).seconds, speed).ticks()

  // The ticks after opens that slot's own time lies between, the one at or before it and the one at or after.
  private fun earliestOf(
    slot: Int,
    opens: Long,
  ): Long = ((firstSlot + slot) * MEDIA_TIMESCALE / grid - opens).coerceAtLeast(0)

  private fun latestOf(
    slot: Int,
    opens: Long,
  ): Long = ((firstSlot + slot) * MEDIA_TIMESCALE + grid - 1) / grid - opens
}

@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertPlayed(
  source: AVAssetTrack,
  opens: Long,
  length: Long,
  row: PlayedRow,
): Boolean {
  val inserted =
    insertTimeRange(
      timeRange = CMTimeRangeMake(start = tickTime(row.read), duration = tickTime(row.sourceTicks)),
      ofTrack = source,
      atTime = tickTime(opens),
      error = null,
    )
  if (inserted && row.sourceTicks != length) {
    scaleTimeRange(CMTimeRangeMake(start = tickTime(opens), duration = tickTime(row.sourceTicks)), tickTime(length))
  }
  return inserted
}

@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertHeld(
  source: AVAssetTrack,
  opens: Long,
  length: Long,
  read: Long,
): Boolean {
  val inserted =
    insertTimeRange(
      timeRange = CMTimeRangeMake(start = tickTime(read), duration = tickTime(1)),
      ofTrack = source,
      atTime = tickTime(opens),
      error = null,
    )
  if (inserted) scaleTimeRange(CMTimeRangeMake(start = tickTime(opens), duration = tickTime(1)), tickTime(length))
  return inserted
}
