package dev.jordond.filmstrip.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetImageGeneratorSucceeded
import platform.AVFoundation.valueWithCMTime
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSValue
import kotlin.coroutines.resume

/**
 * Whether the first frame of this asset's video decodes to a picture.
 *
 * The frame is drawn small, since only whether it decodes matters, not what it shows.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun AVAsset.decodesFirstFrame(): Boolean {
  val generator =
    AVAssetImageGenerator(asset = this).apply {
      maximumSize = CGSizeMake(PROBE_FRAME_PX, PROBE_FRAME_PX)
    }
  return suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { generator.cancelAllCGImageGeneration() }
    generator.generateCGImagesAsynchronouslyForTimes(listOf(NSValue.valueWithCMTime(CMTimeMake(0, 1)))) {
      _,
      image,
      _,
      result,
      _,
      ->
      if (continuation.isActive) continuation.resume(result == AVAssetImageGeneratorSucceeded && image != null)
    }
  }
}

private const val PROBE_FRAME_PX = 64.0
