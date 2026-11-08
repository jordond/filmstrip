package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.NANOS_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVSampleCursor
import platform.AVFoundation.canProvideSampleCursors
import platform.AVFoundation.currentSampleSyncInfo
import platform.AVFoundation.makeSampleCursorWithPresentationTimeStamp
import platform.AVFoundation.presentationTimeStamp
import platform.AVFoundation.segments
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeSubtract
import platform.CoreMedia.kCMTimeZero
import kotlin.time.Duration

/**
 * Where the last sync sample at or before [time] starts on this track, in the track's own timeline,
 * or null when the track will not say.
 *
 * A stream copy has to open on one of these, since every sample between one sync sample and the next
 * is decoded from the samples in front of it. The scan steps a sample cursor, so it reads the track's
 * sample table and none of its media data. It finds the latest sample shown at or before [time], then
 * steps back in presentation order to the first sync sample. It stops when the caller is cancelled.
 *
 * @param cap The most samples one walk steps over.
 * @throws AvLoweringFailure When a walk steps over more than [cap] samples.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun AVAssetTrack.syncSampleAtOrBefore(
  time: Duration,
  cap: Int = SAMPLE_TIMES_CAP,
): Duration? {
  if (!canProvideSampleCursors) return null
  val shift = editShift()
  // The cursor reads the track's media timeline, which the edit shift moves the composed times off.
  val target = CMTimeMake(value = time.inWholeNanoseconds, timescale = NANOS_TIMESCALE)
  val cursor = makeSampleCursorWithPresentationTimeStamp(CMTimeSubtract(target, editShiftTime())) ?: return null
  val context = currentCoroutineContext()
  var steps = 0

  // Whether the cursor moved by one sample, checking for cancellation and the cap first.
  fun step(by: Long): Boolean {
    context.ensureActive()
    if (++steps > cap) throw AvLoweringFailure(pastCap(cap))
    return cursor.stepInPresentationOrderByCount(by) == by
  }

  // The cursor lands on a sample near the cut, so it steps back past it and on to the last one shown by then.
  while (cursor.composedAt(shift) > time) {
    if (!step(-1)) break
  }
  while (step(1)) {
    if (cursor.composedAt(shift) > time) {
      cursor.stepInPresentationOrderByCount(-1)
      break
    }
  }
  while (true) {
    val at = cursor.composedAt(shift)
    if (at <= time && cursor.isFullSync()) return at
    if (!step(-1)) return null
  }
}

// Where the cursor's sample is shown on the track's own timeline, and zero for one before the track opens.
@OptIn(ExperimentalForeignApi::class)
internal fun AVSampleCursor.composedAt(shift: Duration): Duration =
  (presentationTimeStamp.toDuration() + shift).coerceAtLeast(Duration.ZERO)

@OptIn(ExperimentalForeignApi::class)
internal fun AVSampleCursor.isFullSync(): Boolean = currentSampleSyncInfo.useContents { sampleIsFullSync }

/**
 * How far this track's sample times sit from the times it is composed at.
 *
 * A container that drops its opening samples does it with an edit list, which numbers the samples
 * from one place and presents them from another. A sample's own time carries the number, and
 * `insertTimeRange:ofTrack:` asks for the presentation time, so one has to be moved onto the other.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAssetTrack.editShift(): Duration =
  firstEdit()?.timeMapping?.useContents {
    target.start.readValue().toDuration() - source.start.readValue().toDuration()
  } ?: Duration.ZERO

/**
 * The same shift as [editShift], as an exact time in the track's own timescale.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAssetTrack.editShiftTime(): CValue<CMTime> =
  firstEdit()?.timeMapping?.useContents { CMTimeSubtract(target.start.readValue(), source.start.readValue()) }
    ?: kCMTimeZero.readValue()

private fun AVAssetTrack.firstEdit(): AVAssetTrackSegment? =
  segments.filterIsInstance<AVAssetTrackSegment>().firstOrNull { !it.empty }
