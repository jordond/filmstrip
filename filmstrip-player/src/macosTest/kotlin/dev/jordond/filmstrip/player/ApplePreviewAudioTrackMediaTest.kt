package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ChosenAudioTrackContractTest
import dev.jordond.filmstrip.player.contract.HeardTone
import dev.jordond.filmstrip.player.contract.PreviewAudioUnderTest
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PreviewPlan
import dev.jordond.filmstrip.player.internal.previewItem
import dev.jordond.filmstrip.test.ThreeAudio
import dev.jordond.filmstrip.test.ToneAnalysis
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleavedKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderAudioMixOutput
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.asset
import platform.AVFoundation.audioMix
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import kotlin.test.fail

/**
 * The chosen audio track contract on Apple, heard by reading the item a preview plays through its own audio mix.
 *
 * An asset reader mixes the item's composition the way its player does, so what it hands back is what the preview
 * plays, without an audio device.
 */
class ApplePreviewAudioTrackMediaTest : ChosenAudioTrackContractTest(ApplePreviewAudio) {
  init {
    pumpMainRunLoopDuringContracts()
  }
}

@OptIn(ExperimentalForeignApi::class)
private object ApplePreviewAudio : PreviewAudioUnderTest {
  override val source: MediaSource get() = MediaSource.of(appleFixture(ThreeAudio.FILE_NAME))

  override suspend fun heard(clip: Clip): HeardTone {
    val plan =
      withContext(Dispatchers.Default) {
        AvPreviewPlanner(CONTRACT_COMPONENTS).plan(
          Composition(listOf(Track(listOf(clip)))),
          PreviewQualityPolicy.Full,
          ExportSpec(),
        )
      }.shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>().plan
    return withContext(Dispatchers.Default) { mixedAudioOf(previewItem(plan.resolved.toAvComposition())) }
  }

  // Interleaved 32-bit float at the fixture's rate, downmixed to mono.
  private fun mixedAudioOf(item: AVPlayerItem): HeardTone {
    val asset = item.asset
    val output =
      AVAssetReaderAudioMixOutput(
        audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio),
        audioSettings =
          mapOf(
            AVFormatIDKey to kAudioFormatLinearPCM,
            AVSampleRateKey to SAMPLE_RATE.toDouble(),
            AVNumberOfChannelsKey to CHANNELS,
            AVLinearPCMBitDepthKey to BIT_DEPTH,
            AVLinearPCMIsFloatKey to true,
            AVLinearPCMIsBigEndianKey to false,
            AVLinearPCMIsNonInterleavedKey to false,
          ),
      ).apply { audioMix = item.audioMix }
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: fail("no reader for the preview")
    reader.addOutput(output)
    if (!reader.startReading()) fail("the reader refused to start: ${reader.error?.localizedDescription}")

    val chunks = mutableListOf<FloatArray>()
    try {
      while (true) {
        val buffer = output.copyNextSampleBuffer() ?: break
        try {
          val block = CMSampleBufferGetDataBuffer(buffer) ?: continue
          val bytes = CMBlockBufferGetDataLength(block).toInt()
          if (bytes == 0) continue
          val interleaved = FloatArray(bytes / Float.SIZE_BYTES)
          interleaved.usePinned { pinned ->
            val status = CMBlockBufferCopyDataBytes(block, 0u, bytes.toULong(), pinned.addressOf(0))
            if (status != 0) fail("could not copy $bytes bytes of the preview's audio, status $status")
          }
          chunks += ToneAnalysis.toMono(interleaved, CHANNELS)
        } finally {
          CFRelease(buffer)
        }
      }
    } finally {
      reader.cancelReading()
    }
    return HeardTone(chunks.fold(FloatArray(0)) { all, chunk -> all + chunk }, SAMPLE_RATE)
  }

  private const val SAMPLE_RATE = 48_000
  private const val CHANNELS = 2
  private const val BIT_DEPTH = 32
}
