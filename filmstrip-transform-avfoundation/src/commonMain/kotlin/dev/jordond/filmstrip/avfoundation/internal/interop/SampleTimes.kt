package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.NANOS_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVSampleCursor
import platform.AVFoundation.asset
import platform.AVFoundation.canProvideSampleCursors
import platform.AVFoundation.duration
import platform.AVFoundation.makeSampleCursorWithPresentationTimeStamp
import platform.AVFoundation.presentationTimeStamp
import platform.AVFoundation.timeRange
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeConvertScale
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.CMTimeSubtract
import platform.CoreMedia.kCMTimeFlags_Indefinite
import platform.CoreMedia.kCMTimeFlags_NegativeInfinity
import platform.CoreMedia.kCMTimeFlags_PositiveInfinity
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreMedia.kCMTimeRoundingMethod_RoundTowardNegativeInfinity
import platform.CoreMedia.kCMTimeRoundingMethod_RoundTowardPositiveInfinity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The presentation times of a run of a track's samples, in the track's own timeline and in presentation order.
 *
 * Each sample is also read at the first tick at or after its own time, since a range laid on a composition track can
 * only open on a tick and AVFoundation shows the sample whose time is at or before the time it reads.
 *
 * @property times Each sample's own time.
 * @property endTick The tick the track's media ends on, which no read can reach.
 * @property reachesEnd Whether the last of [times] is the track's own last sample.
 */
internal class SampleTimes(
  val times: List<Duration>,
  private val readTicks: LongArray,
  val endTick: Long,
  private val reachesEnd: Boolean,
) {
  /**
   * The tick sample [index] is read from.
   */
  fun readTickOf(index: Int): Long = readTicks[index]

  /**
   * The last tick a read still shows sample [index] on, the one before the next sample is read from.
   *
   * The last sample read runs to the end of the track when it is the track's own last, and is only known to hold its
   * own tick when a later sample may follow it.
   */
  fun lastTickOf(index: Int): Long =
    when {
      index < readTicks.lastIndex -> readTicks[index + 1] - 1
      reachesEnd -> endTick - 1
      else -> readTicks[index]
    }

  /**
   * The sample a read shows in place of sample [index], which is [index] itself unless later samples are read from
   * the same tick.
   *
   * AVFoundation shows the last of the samples sharing a tick there, so no read can show the others. Those show the
   * last of them, the sample nearest after them, instead.
   */
  fun shownOf(index: Int): Int {
    var shown = index
    while (shown < readTicks.lastIndex && readTicks[shown + 1] == readTicks[index]) shown++
    return shown
  }

  /**
   * The tick a single tick read holding sample [index] opens on, inside the track's media.
   *
   * [index] is a sample a read can show, one [shownOf] gives back.
   */
  fun heldTickOf(index: Int): Long = minOf(readTicks[index], endTick - 1)
}

/**
 * The times of this track's samples from the last one at or before [from] to the first one at or after [to], or null
 * when the track will not say or holds no samples.
 *
 * The scan steps a sample cursor, so it reads the track's sample table and none of its media data. It reads a
 * second either side of the two times first, and widens until it holds a sample on each side or reaches the ends of
 * the track. It stops when the caller is cancelled.
 *
 * @param cap The most samples one scan reads.
 * @throws AvLoweringFailure When a scan reaches more than [cap] samples.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun AVAssetTrack.sampleTimesAround(
  from: Duration,
  to: Duration,
  cap: Int = SAMPLE_TIMES_CAP,
): SampleTimes? {
  val trackEnd = mediaEnd()
  val end = trackEnd.toDuration()
  val endTick =
    CMTimeConvertScale(trackEnd, MEDIA_TIMESCALE, kCMTimeRoundingMethod_RoundTowardNegativeInfinity)
      .useContents { value }
  var margin = 1.seconds
  while (true) {
    val opens = (from - margin).coerceAtLeast(Duration.ZERO)
    val closes = minOf(to + margin, end)
    val composed = composedTimesWithin(opens, closes, cap) ?: return null
    val reachesBack = opens == Duration.ZERO || composed.nanos[0] <= from.inWholeNanoseconds
    val reachesOn = closes >= end || composed.nanos[composed.size - 1] >= to.inWholeNanoseconds
    if (reachesBack && reachesOn) {
      return SampleTimes(
        times = List(composed.size) { composed.nanos[it].nanoseconds },
        readTicks = composed.ticks.copyOf(composed.size),
        endTick = endTick,
        reachesEnd = closes >= end,
      )
    }
    margin *= 2
  }
}

// Each distinct sample time from a scan, in nanoseconds and as the tick it is read from, both sorted. A later time
// never reads from an earlier tick, so sorting the two apart keeps each pair together.
private class ComposedTimes(
  val nanos: LongArray,
  val ticks: LongArray,
  val size: Int,
)

// Each sample from the last one presented at or before opens to the first one presented at or after closes, as its own
// time and the tick it is read from, checking for cancellation and the cap before each sample.
@OptIn(ExperimentalForeignApi::class)
private suspend fun AVAssetTrack.composedTimesWithin(
  opens: Duration,
  closes: Duration,
  cap: Int,
): ComposedTimes? {
  if (!canProvideSampleCursors) return null
  val shift = editShiftTime()
  val shifted = shift.useContents { value != 0L }
  // The cursor reads the track's media timeline, which the edit shift moves the composed times off.
  val opening = CMTimeMake(value = opens.inWholeNanoseconds, timescale = NANOS_TIMESCALE)
  val cursor = makeSampleCursorWithPresentationTimeStamp(CMTimeSubtract(opening, shift)) ?: return null
  val opensNanos = opens.inWholeNanoseconds
  val closesNanos = closes.inWholeNanoseconds

  val context = currentCoroutineContext()
  var nanos = LongArray(INITIAL_SAMPLES)
  var ticks = LongArray(INITIAL_SAMPLES)
  var count = 0
  // The cursor lands on a sample near opens, so it steps back until it sits at or before it.
  while (cursor.composedNanos(shift, shifted) > opensNanos && cursor.stepInPresentationOrderByCount(-1) != 0L) {
    context.ensureActive()
  }
  var reading = true
  while (reading) {
    context.ensureActive()
    if (count > cap) throw AvLoweringFailure(pastCap(cap))
    if (count == nanos.size) {
      // Never past one over the cap, which is as far as the scan reads before it refuses.
      val grown = minOf(count * 2L, cap + 1L).toInt()
      nanos = nanos.copyOf(grown)
      ticks = ticks.copyOf(grown)
    }
    val presented = cursor.presentationTimeStamp
    // A plain time is read off its fields, which costs far less than a CoreMedia call for each sample.
    (if (shifted) CMTimeAdd(presented, shift) else presented).useContents {
      if ((flags and NUMERIC_FLAGS) == kCMTimeFlags_Valid) {
        nanos[count] = (value.toDouble() / timescale).coerceAtLeast(0.0).seconds.inWholeNanoseconds
        ticks[count] = ticksAtOrAfter(value, timescale).coerceAtLeast(0)
      } else {
        val time = readValue()
        nanos[count] = time.toDuration().inWholeNanoseconds
        ticks[count] = time.readTick()
      }
    }
    reading = nanos[count] < closesNanos && cursor.stepInPresentationOrderByCount(1) != 0L
    count++
  }

  nanos.sort(0, count)
  ticks.sort(0, count)
  var distinct = 0
  for (index in 0 until count) {
    if (distinct == 0 || nanos[index] != nanos[distinct - 1]) {
      nanos[distinct] = nanos[index]
      ticks[distinct] = ticks[index]
      distinct++
    }
  }
  return ComposedTimes(nanos, ticks, distinct)
}

// Where the cursor's sample is composed, in nanoseconds.
@OptIn(ExperimentalForeignApi::class)
private fun AVSampleCursor.composedNanos(
  shift: CValue<CMTime>,
  shifted: Boolean,
): Long =
  (if (shifted) CMTimeAdd(presentationTimeStamp, shift) else presentationTimeStamp).toDuration().inWholeNanoseconds

/**
 * Where this track's media ends, from its own time range.
 *
 * A transport stream gives its tracks no time range, so the end is the asset's duration there.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAssetTrack.mediaEnd(): CValue<CMTime> {
  val own = CMTimeRangeGetEnd(timeRange)
  val valid = own.useContents { (flags and kCMTimeFlags_Valid) != 0u }
  return if (valid) own else asset?.duration ?: own
}

// The first tick at or after this time, and zero for a time before the track opens.
@OptIn(ExperimentalForeignApi::class)
private fun CValue<CMTime>.readTick(): Long =
  CMTimeConvertScale(this, MEDIA_TIMESCALE, kCMTimeRoundingMethod_RoundTowardPositiveInfinity)
    .useContents { value }
    .coerceAtLeast(0)

/**
 * A tick read from [SampleTimes] as a time at [MEDIA_TIMESCALE].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun tickTime(tick: Long): CValue<CMTime> = CMTimeMake(value = tick, timescale = MEDIA_TIMESCALE)

private const val INITIAL_SAMPLES = 1_024

/**
 * The most samples [sampleTimesAround] reads in one scan, and [syncSampleAtOrBefore] steps over in one walk, well
 * above the 3,456,000 frames of an eight hour clip at 120 fps.
 */
internal const val SAMPLE_TIMES_CAP: Int = 10_000_000

/**
 * Why a scan or walk past [cap] samples of one video track is refused.
 */
internal fun pastCap(cap: Int): String =
  "A clip reads the times of more than $cap frames of one video track, which is more than this backend lays for one " +
    "clip."

// The flags a time has to have only the first of to be a plain number of seconds.
private val NUMERIC_FLAGS =
  kCMTimeFlags_Valid or kCMTimeFlags_PositiveInfinity or kCMTimeFlags_NegativeInfinity or kCMTimeFlags_Indefinite

// The first tick at or after value over timescale, as CMTimeConvertScale rounds it toward positive infinity. The whole
// seconds are scaled apart from the rest so a long track at a fine timescale doesn't overflow.
private fun ticksAtOrAfter(
  value: Long,
  timescale: Int,
): Long {
  val whole = value.floorDiv(timescale.toLong())
  val part = (value - whole * timescale) * MEDIA_TIMESCALE
  return whole * MEDIA_TIMESCALE + (part + timescale - 1) / timescale
}
