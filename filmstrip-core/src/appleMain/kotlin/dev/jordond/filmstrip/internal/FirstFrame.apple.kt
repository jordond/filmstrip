package dev.jordond.filmstrip.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithUnsignedInt

/**
 * Whether the first sample of [track] decodes to a picture through an asset reader, the way an export reads it.
 *
 * The reader's settings are a real dictionary, since a bridged Kotlin map is not one once it crosses into Apple's media
 * APIs. A reader that cannot be set up at all answers true, since that says nothing about the bytes.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun AVAsset.decodesFirstFrame(track: AVAssetTrack): Boolean {
  val reader = AVAssetReader.assetReaderWithAsset(this, error = null) ?: return true
  val settings =
    NSDictionary.dictionaryWithObject(
      NSNumber.numberWithUnsignedInt(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
      forKey = NSString.create(string = PIXEL_FORMAT_KEY),
    )
  val output = AVAssetReaderTrackOutput(track = track, outputSettings = settings)
  if (!reader.canAddOutput(output)) return true
  reader.addOutput(output)
  if (!reader.startReading()) return false
  try {
    val sample = output.copyNextSampleBuffer() ?: return false
    try {
      return CMSampleBufferGetImageBuffer(sample) != null
    } finally {
      CFRelease(sample)
    }
  } finally {
    reader.cancelReading()
  }
}

private const val PIXEL_FORMAT_KEY = "PixelFormatType"
