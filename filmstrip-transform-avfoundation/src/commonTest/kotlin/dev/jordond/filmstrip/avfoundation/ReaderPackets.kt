package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.interop.editShift
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderSampleReferenceOutput
import platform.AVFoundation.AVAssetTrack
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
import kotlin.time.Duration

/**
 * Every sample an asset reader hands back for [track], in decode order, on the track's own timeline.
 *
 * The reader is independent of the sample cursor the backend counts a copy with, so a test can check that count
 * against it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun readerPacketsOf(track: AVAssetTrack): List<CopyPacket> {
  val reader = AVAssetReader.assetReaderWithAsset(track.asset.shouldNotBeNull(), error = null).shouldNotBeNull()
  val output = AVAssetReaderSampleReferenceOutput(track = track)
  reader.addOutput(output)
  reader.startReading().shouldBeTrue()
  val shift = track.editShift()
  val read = mutableListOf<CopyPacket>()
  while (true) {
    val more =
      autoreleasepool {
        val buffer = output.copyNextSampleBuffer() ?: return@autoreleasepool false
        if (CMSampleBufferGetNumSamples(buffer) > 0) {
          val at = (CMSampleBufferGetPresentationTimeStamp(buffer).toDuration() + shift).coerceAtLeast(Duration.ZERO)
          read += CopyPacket(at, buffer.isSyncSample())
        }
        CFRelease(buffer)
        true
      }
    if (!more) break
  }
  reader.cancelReading()
  return read
}

// A sample is marked only when it is not a sync sample, so one carrying no attachments is one.
@OptIn(ExperimentalForeignApi::class)
private fun CMSampleBufferRef.isSyncSample(): Boolean {
  val attachments = CMSampleBufferGetSampleAttachmentsArray(this, createIfNecessary = false) ?: return true
  if (CFArrayGetCount(attachments) == 0L) return true
  val sample: CFDictionaryRef = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret() ?: return true
  val notSync: CFBooleanRef =
    CFDictionaryGetValue(sample, kCMSampleAttachmentKey_NotSync)?.reinterpret() ?: return true
  return !CFBooleanGetValue(notSync)
}
