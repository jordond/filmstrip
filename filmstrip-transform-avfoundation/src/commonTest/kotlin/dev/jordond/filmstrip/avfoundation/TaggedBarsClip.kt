package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_HEIGHT_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_WIDTH_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSFileManager
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * Writes [frames] frames of H.264 to [path], [bars] side by side, tagged with [primaries] and [matrix].
 *
 * No published fixture carries these tags, and an H.264 writer runs on the simulator too. The default bars stay inside
 * BT.709 when read as BT.2020 or Display P3, and [OUT_OF_GAMUT_BARS] do not.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeTaggedBars(
  path: String,
  frames: Int,
  primaries: String,
  matrix: String,
  bars: List<Triple<Int, Int, Int>> = BARS,
) {
  NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  val writer =
    AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4, error = null)
      ?: error("no writer for $path")
  val input =
    AVAssetWriterInput(
      mediaType = AVMediaTypeVideo,
      outputSettings =
        mapOf(
          AVVideoCodecKey to AVVideoCodecTypeH264,
          AVVideoWidthKey to CLIP_WIDTH,
          AVVideoHeightKey to CLIP_HEIGHT,
          AVVideoColorPropertiesKey to
            mapOf(
              AVVideoColorPrimariesKey to primaries,
              AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
              AVVideoYCbCrMatrixKey to matrix,
            ),
        ),
    )
  input.expectsMediaDataInRealTime = false
  val adaptor =
    AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput = input,
      sourcePixelBufferAttributes =
        mapOf(
          PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt(),
          PIXEL_BUFFER_WIDTH_KEY to CLIP_WIDTH,
          PIXEL_BUFFER_HEIGHT_KEY to CLIP_HEIGHT,
        ),
    )
  writer.addInput(input)
  check(writer.startWriting()) { "the writer refused to start: ${writer.error?.localizedDescription}" }
  writer.startSessionAtSourceTime(kCMTimeZero.readValue())
  repeat(frames) { index ->
    while (!input.readyForMoreMediaData) NSThread.sleepForTimeInterval(READY_POLL_SECONDS)
    val buffer = barsBuffer(adaptor, bars)
    check(adaptor.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(index.toLong(), CLIP_FRAME_RATE))) {
      "frame $index was refused: ${writer.error?.localizedDescription}"
    }
    CVPixelBufferRelease(buffer)
  }
  input.markAsFinished()
  val done = dispatch_semaphore_create(0)
  writer.finishWritingWithCompletionHandler { dispatch_semaphore_signal(done) }
  dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
  check(writer.error == null) { "the clip did not finish: ${writer.error?.localizedDescription}" }
}

@OptIn(ExperimentalForeignApi::class)
private fun barsBuffer(
  adaptor: AVAssetWriterInputPixelBufferAdaptor,
  bars: List<Triple<Int, Int, Int>>,
): CVPixelBufferRef =
  memScoped {
    val out = alloc<CVPixelBufferRefVar>()
    val pool = adaptor.pixelBufferPool ?: error("the adaptor has no buffer pool")
    CVPixelBufferPoolCreatePixelBuffer(null, pool, out.ptr)
    val buffer = out.value ?: error("the pool gave no buffer")
    CVPixelBufferLockBaseAddress(buffer, 0u)
    val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<UByteVar>() ?: error("no base address")
    val row = CVPixelBufferGetBytesPerRow(buffer).toInt()
    for (y in 0 until CLIP_HEIGHT) {
      for (x in 0 until CLIP_WIDTH) {
        val (red, green, blue) = bars[x * bars.size / CLIP_WIDTH]
        val offset = y * row + x * BGRA_BYTES
        base[offset] = blue.toUByte()
        base[offset + 1] = green.toUByte()
        base[offset + 2] = red.toUByte()
        base[offset + 3] = UByte.MAX_VALUE
      }
    }
    CVPixelBufferUnlockBaseAddress(buffer, 0u)
    buffer
  }

/**
 * Eight saturated bars that land outside BT.709 when read as BT.2020 or Display P3, each clipping at least one channel.
 */
internal val OUT_OF_GAMUT_BARS =
  listOf(
    Triple(60, 220, 60),
    Triple(70, 215, 70),
    Triple(80, 210, 75),
    Triple(85, 205, 85),
    Triple(230, 40, 60),
    Triple(220, 60, 70),
    Triple(215, 75, 80),
    Triple(40, 200, 200),
  )

private val BARS =
  listOf(
    Triple(200, 140, 110),
    Triple(120, 180, 130),
    Triple(120, 130, 190),
    Triple(170, 170, 170),
    Triple(190, 170, 110),
    Triple(120, 170, 170),
    Triple(170, 120, 160),
    Triple(90, 90, 90),
  )

private const val CLIP_WIDTH = 640
private const val CLIP_HEIGHT = 360
private const val CLIP_FRAME_RATE = 30
private const val BGRA_BYTES = 4
private const val READY_POLL_SECONDS = 0.005
