package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.time.NANOS_TIMESCALE
import dev.jordond.filmstrip.transform.internal.copy.COPY_PACKET_MARGIN
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.MAX_COPY_PACKET_RATE
import dev.jordond.filmstrip.transform.internal.copy.copyPacketCap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.canProvideSampleCursors
import platform.AVFoundation.makeSampleCursorWithPresentationTimeStamp
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeSubtract
import kotlin.time.Duration

/**
 * This track's samples in decode order, from the sync sample at or before [from] to the first one presented at or past
 * [to] and [COPY_PACKET_MARGIN], on the track's own timeline. Null when the track will not say.
 *
 * The walk steps a sample cursor, so it reads the sample table and none of the media data. It stops when the caller is
 * cancelled.
 *
 * @param cap The most samples one walk reads, [copyPacketCap] over the window by default.
 * @throws AvLoweringFailure When a walk reads more than [cap] samples.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun AVAssetTrack.copyPacketsWithin(
  from: Duration,
  to: Duration,
  cap: Int = copyPacketCap(from, to),
): List<CopyPacket>? {
  if (!canProvideSampleCursors) return null
  val opening = syncSampleAtOrBefore(from) ?: return null
  val shift = editShift()
  val target = CMTimeMake(value = opening.inWholeNanoseconds, timescale = NANOS_TIMESCALE)
  val cursor = makeSampleCursorWithPresentationTimeStamp(CMTimeSubtract(target, editShiftTime())) ?: return null
  val closes = to + COPY_PACKET_MARGIN
  val context = currentCoroutineContext()

  // The cursor lands on a sample presented near the opening, which can sit either side of it.
  while (cursor.composedAt(shift) > opening && cursor.stepInPresentationOrderByCount(-1) == -1L) {
    context.ensureActive()
  }
  while (cursor.composedAt(shift) < opening && cursor.stepInPresentationOrderByCount(1) == 1L) {
    context.ensureActive()
  }

  return buildList {
    while (true) {
      context.ensureActive()
      if (size >= cap) throw AvLoweringFailure(pastCopyCap(cap, to))
      val at = cursor.composedAt(shift)
      add(CopyPacket(at, cursor.isFullSync()))
      if (at >= closes || cursor.stepInDecodeOrderByCount(1) != 1L) break
    }
  }
}

// Why a copy whose walk listed more than cap samples up to to and its margin is refused.
private fun pastCopyCap(
  cap: Int,
  to: Duration,
): String =
  "A copy lists more than $cap video samples up to ${to + COPY_PACKET_MARGIN}, past the $MAX_COPY_PACKET_RATE fps a " +
    "copy reads its samples at."
