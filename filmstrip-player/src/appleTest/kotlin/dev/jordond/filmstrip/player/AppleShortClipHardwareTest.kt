package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.MOVING_PAN
import dev.jordond.filmstrip.player.contract.MOVING_RUN
import dev.jordond.filmstrip.player.contract.ShortClipContractTest
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.Foundation.NSURL
import kotlin.test.fail

/**
 * The short clip contract on AVFoundation. Frames are read where the encoder takes them, and the file comes from the
 * whole export, audio pre-stretch included.
 */
class AppleShortClipHardwareTest : ShortClipContractTest(AppleClipParity) {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @OptIn(ExperimentalForeignApi::class)
  override suspend fun writtenFrameCount(composition: EditComposition): Long {
    val path = AppleClipParity.writtenPath(composition)
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val track =
      asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
        ?: fail("the file holds no video")
    val output = AVAssetReaderTrackOutput(track = track, outputSettings = null)
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: fail("no reader for the file")
    reader.addOutput(output)
    if (!reader.startReading()) fail("the reader refused to start: ${reader.error?.localizedDescription}")
    try {
      var frames = 0L
      while (true) {
        val buffer = output.copyNextSampleBuffer() ?: return frames
        try {
          frames += CMSampleBufferGetNumSamples(buffer)
        } finally {
          CFRelease(buffer)
        }
      }
    } finally {
      reader.cancelReading()
    }
  }

  override suspend fun sourceAt(rate: Int): MediaSource {
    val moving = Clip(fixtureClip, MOVING_RUN, effects = listOf(MOVING_PAN))
    val path = AppleClipParity.export(EditComposition(listOf(Track(listOf(moving)))), ExportSpec(frameRate = rate))
    return MediaSource.of(path)
  }
}
