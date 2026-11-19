package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ClipParityBackend
import dev.jordond.filmstrip.player.contract.HdrParityBackend
import dev.jordond.filmstrip.player.contract.HdrSolidParityContractTest
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.HdrPreviewReadback
import dev.jordond.filmstrip.player.internal.tenBitCodes
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.Foundation.NSURL
import kotlin.test.fail
import kotlin.time.Duration

internal class AppleHdrSolidParityHardwareTest : HdrSolidParityContractTest(AppleHdrParity) {
  init {
    pumpMainRunLoopDuringContracts()
  }
}

/**
 * AVFoundation's side of the suites that hold a kept HDR grade's preview to its export.
 *
 * The export is decoded back to ten-bit biplanar buffers by a track reader that never saw the composition, and read
 * through the same buffer reader the preview's codes come off.
 */
internal object AppleHdrParity : HdrParityBackend, ClipParityBackend by AppleClipParity {
  private val exports = mutableMapOf<Composition, String>()

  override suspend fun hdrFixture(transfer: HdrTransfer): MediaSource =
    MediaSource.of(
      when (transfer) {
        HdrTransfer.Pq -> appleFixture("android_export_hdr.mp4")
        HdrTransfer.Hlg -> appleFixture("android_export_hdr_hlg.mp4")
      },
    )

  override fun hdrReadback(engine: PlayerEngine): HdrPreviewReadback = (engine as AvPlayerEngine).hdrReadback

  override suspend fun exportHdrCodes(
    composition: Composition,
    position: Duration,
    x: Double,
    y: Double,
  ): IntArray {
    val path =
      exports.getOrPut(composition) {
        AppleClipParity.export(
          composition,
          ExportSpec(hdr = HdrMode.KeepHdr, bitrate = PREVIEW_PARITY_EXPORT_BITRATE),
        )
      }
    return codesAt(path, position, x, y)
  }

  // The last frame the file shows at or before position, read as codes at the given fractions of its size.
  @OptIn(ExperimentalForeignApi::class)
  private fun codesAt(
    path: String,
    position: Duration,
    x: Double,
    y: Double,
  ): IntArray {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
    val output =
      AVAssetReaderTrackOutput(
        track = track,
        outputSettings = mapOf(PIXEL_FORMAT_KEY to kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt()),
      )
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: fail("no reader for $path")
    reader.addOutput(output)
    if (!reader.startReading()) fail("$path could not be read")
    var shown: CMSampleBufferRef? = null
    try {
      while (true) {
        val next = output.copyNextSampleBuffer() ?: break
        if (CMSampleBufferGetPresentationTimeStamp(next).toDuration() > position) {
          CFRelease(next)
          break
        }
        shown?.let(::CFRelease)
        shown = next
      }
      val buffer = shown?.let(::CMSampleBufferGetImageBuffer) ?: fail("$path has no frame at $position")
      val width = CVPixelBufferGetWidth(buffer).toInt()
      val height = CVPixelBufferGetHeight(buffer).toInt()
      val at = ((height * y).toInt() * width + (width * x).toInt()) * CODES
      val codes = buffer.tenBitCodes()
      return IntArray(CODES) { codes[at + it].toInt() }
    } finally {
      shown?.let(::CFRelease)
      reader.cancelReading()
    }
  }

  private const val CODES = 3
}
