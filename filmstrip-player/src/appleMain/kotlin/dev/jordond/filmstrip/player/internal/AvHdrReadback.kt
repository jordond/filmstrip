package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.videoCompositionOutput
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.player.PlaybackError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import kotlin.time.Duration

/**
 * Reads a kept HDR grade's preview frame out of the reader output the export's writer takes its frames from.
 *
 * The player shows the same video composition, and the output renders it into ten-bit biplanar video range buffers,
 * which are the codes the encoder is handed. Each pixel takes the Cb and Cr of the two by two block it sits in.
 *
 * @param composition The graph the player is showing, or null before one is loaded.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvHdrReadback(
  private val composition: () -> AvComposition?,
) : HdrPreviewReadback {
  override suspend fun hdrFrameAt(position: Duration): HdrPreviewResult {
    val av = composition() ?: return failure(NO_COMPOSITION)
    val transfer = av.transfer?.takeIf { av.encodesHdr } ?: return failure(NO_HDR_GRADE)
    val frame =
      withContext(Dispatchers.IO) { read(av, position, transfer) }
        ?: return HdrPreviewResult.Failure(PlaybackError.Underlying(NO_CODE, noFrame(position)))
    return HdrPreviewResult.Success(frame)
  }

  private fun read(
    av: AvComposition,
    position: Duration,
    transfer: HdrTransfer,
  ): HdrPreviewFrame? {
    val reader = AVAssetReader.assetReaderWithAsset(av.composition, error = null) ?: return null
    val output = av.videoCompositionOutput(av.composition.tracksWithMediaType(AVMediaTypeVideo))
    reader.addOutput(output)
    reader.timeRange = CMTimeRangeMake(position.toCMTime(), kCMTimePositiveInfinity.readValue())
    if (!reader.startReading()) return null
    try {
      val sample = output.copyNextSampleBuffer() ?: return null
      try {
        val buffer = CMSampleBufferGetImageBuffer(sample) ?: return null
        val size = Size(CVPixelBufferGetWidth(buffer).toInt(), CVPixelBufferGetHeight(buffer).toInt())
        val at = CMSampleBufferGetPresentationTimeStamp(sample).toDuration()
        return HdrPreviewFrame(buffer.tenBitCodes(), size, at, transfer)
      } finally {
        CFRelease(sample)
      }
    } finally {
      reader.cancelReading()
    }
  }

  private fun failure(message: String): HdrPreviewResult =
    HdrPreviewResult.Failure(PlaybackError.SourceUnreadable(message))

  private companion object {
    val NO_CODE = PlaybackError.Underlying.NO_PLATFORM_CODE
    const val NO_COMPOSITION = "No composition is loaded, so there is no frame to read back."
    const val NO_HDR_GRADE = "The composition is on an SDR grade, so there are no HDR codes to read."

    fun noFrame(at: Duration): String = "The video composition rendered no frame at $at."
  }
}

/**
 * The luma, Cb and Cr codes of every pixel in this ten-bit biplanar video range buffer, row major, three a pixel.
 *
 * Each sample holds its ten bits at the top of sixteen. The second plane interleaves Cb and Cr at half size both ways,
 * so a pixel takes the pair of the two by two block it sits in.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CVPixelBufferRef.tenBitCodes(): ShortArray {
  CVPixelBufferLockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
  try {
    val width = CVPixelBufferGetWidth(this).toInt()
    val height = CVPixelBufferGetHeight(this).toInt()
    val luma = CVPixelBufferGetBaseAddressOfPlane(this, 0u)?.reinterpret<UShortVar>() ?: error(NO_PLANES)
    val chroma = CVPixelBufferGetBaseAddressOfPlane(this, 1u)?.reinterpret<UShortVar>() ?: error(NO_PLANES)
    val lumaRow = CVPixelBufferGetBytesPerRowOfPlane(this, 0u).toInt() / Short.SIZE_BYTES
    val chromaRow = CVPixelBufferGetBytesPerRowOfPlane(this, 1u).toInt() / Short.SIZE_BYTES
    val codes = ShortArray(width * height * CODES)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val at = (y * width + x) * CODES
        val pair = (y / 2) * chromaRow + (x / 2) * 2
        codes[at] = luma[y * lumaRow + x].code()
        codes[at + 1] = chroma[pair].code()
        codes[at + 2] = chroma[pair + 1].code()
      }
    }
    return codes
  } finally {
    CVPixelBufferUnlockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
  }
}

private fun UShort.code(): Short = (toInt() shr PADDING_BITS).toShort()

private const val CODES = 3
private const val PADDING_BITS = 6
private const val NO_PLANES = "The buffer has no planes to read."
