package dev.jordond.filmstrip.avfoundation.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderSampleReferenceOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.segments
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
import kotlin.time.Duration

/**
 * Where the last sync sample at or before [time] starts on this track, in the track's own timeline,
 * or null when the track will not say.
 *
 * A stream copy has to open on one of these, since every sample between one sync sample and the next
 * is decoded from the samples in front of it. The scan walks sample references rather than samples,
 * so it reads the track's sample table and none of its media data.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun AVAssetTrack.syncSampleAtOrBefore(time: Duration): Duration? {
  val reader = AVAssetReader.assetReaderWithAsset(asset ?: return null, error = null) ?: return null
  val output = AVAssetReaderSampleReferenceOutput(track = this)
  if (!reader.canAddOutput(output)) return null
  reader.addOutput(output)
  val shift = editShift()
  // Nothing past the cut can be the sample the cut opens on, so the scan stops there rather than
  // running to the end of the file. Widened by the shift in both directions, since which timeline
  // the reader's own range reads in is not the one the sample times below are known to be in, and
  // by a tick again, since a range's end is exclusive and a cut already sitting on a sync sample
  // has to find that one rather than the one before it.
  reader.timeRange = timeRangeOf(Duration.ZERO, time + shift.absoluteValue + MEDIA_TICK)
  if (!reader.startReading()) return null

  var opening: Duration? = null
  var draining = true
  while (draining) {
    autoreleasepool {
      val buffer = output.copyNextSampleBuffer()
      if (buffer == null) {
        draining = false
      } else {
        // Samples arrive in decode order, so the last one read is not the latest one shown.
        val at = (CMSampleBufferGetPresentationTimeStamp(buffer).toDuration() + shift).coerceAtLeast(Duration.ZERO)
        val best = opening
        if (at <= time && buffer.isSyncSample() && (best == null || at > best)) opening = at
        // copyNextSampleBuffer follows the create rule.
        CFRelease(buffer)
      }
    }
  }

  reader.cancelReading()
  return opening
}

/**
 * How far this track's sample times sit from the times it is composed at.
 *
 * A container that drops its opening samples does it with an edit list, which numbers the samples
 * from one place and presents them from another. A sample reference carries the number, and
 * `insertTimeRange:ofTrack:` asks for the presentation time, so one has to be moved onto the other.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAssetTrack.editShift(): Duration {
  val segment = segments.filterIsInstance<AVAssetTrackSegment>().firstOrNull { !it.empty } ?: return Duration.ZERO

  return segment.timeMapping.useContents {
    target.start.readValue().toDuration() - source.start.readValue().toDuration()
  }
}

/**
 * Whether a decoder could open on this sample.
 *
 * A sample is marked only when it is *not* a sync sample, so a buffer carrying no attachments at
 * all is one.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CMSampleBufferRef.isSyncSample(): Boolean {
  val attachments = CMSampleBufferGetSampleAttachmentsArray(this, createIfNecessary = false) ?: return true
  if (CFArrayGetCount(attachments) == 0L) return true
  val sample: CFDictionaryRef = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret() ?: return true
  val notSync: CFBooleanRef =
    CFDictionaryGetValue(sample, kCMSampleAttachmentKey_NotSync)?.reinterpret() ?: return true

  return !CFBooleanGetValue(notSync)
}
