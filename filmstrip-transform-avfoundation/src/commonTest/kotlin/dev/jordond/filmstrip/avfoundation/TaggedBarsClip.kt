package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_HEIGHT_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_WIDTH_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
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
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.getBytes
import platform.Foundation.writeToFile
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
) = writeBars(
  path = path,
  frames = frames,
  width = CLIP_WIDTH,
  height = CLIP_HEIGHT,
  bars = bars,
  colorProperties =
    mapOf(
      AVVideoColorPrimariesKey to primaries,
      AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
      AVVideoYCbCrMatrixKey to matrix,
    ),
)

/**
 * Writes [frames] frames of H.264 to [path] carrying EBU primaries and a BT.601 matrix.
 *
 * The writer takes those primaries on macOS only, so this writes SMPTE C on every OS and rewrites the primaries in the
 * file's `colr` box, the one a reader takes a track's colour from.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun writeEbuBars(
  path: String,
  frames: Int,
) {
  writeTaggedBars(path, frames, AVVideoColorPrimaries_SMPTE_C.orEmpty(), AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty())
  val data = NSData.dataWithContentsOfFile(path) ?: error("no file at $path")
  val bytes = ByteArray(data.length.toInt())
  bytes.usePinned { pinned -> data.getBytes(pinned.addressOf(0), data.length) }
  val colr = bytes.indexOfBox("colr")
  check(colr >= 0) { "the writer left no colr box in $path" }
  bytes[colr + COLR_PRIMARIES_OFFSET] = 0
  bytes[colr + COLR_PRIMARIES_OFFSET + 1] = EBU_PRIMARIES.toByte()
  val written = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
  check(written.writeToFile(path, atomically = true)) { "could not rewrite $path" }
}

private fun ByteArray.indexOfBox(name: String): Int {
  val tag = name.encodeToByteArray()
  return (0..size - tag.size).firstOrNull { at -> tag.indices.all { this[at + it] == tag[it] } } ?: -1
}

/**
 * Writes [frames] frames of H.264 to [path] at [width] by [height], with no colour tags of its own.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeUntaggedBars(
  path: String,
  frames: Int,
  width: Int,
  height: Int,
) = writeBars(path, frames, width, height, BARS, colorProperties = null)

@OptIn(ExperimentalForeignApi::class)
private fun writeBars(
  path: String,
  frames: Int,
  width: Int,
  height: Int,
  bars: List<Triple<Int, Int, Int>>,
  colorProperties: Map<Any?, Any?>?,
) {
  NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  val writer =
    AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4, error = null)
      ?: error("no writer for $path")
  val input =
    AVAssetWriterInput(
      mediaType = AVMediaTypeVideo,
      outputSettings =
        buildMap<Any?, Any?> {
          put(AVVideoCodecKey, AVVideoCodecTypeH264)
          put(AVVideoWidthKey, width)
          put(AVVideoHeightKey, height)
          colorProperties?.let { put(AVVideoColorPropertiesKey, it) }
        },
    )
  input.expectsMediaDataInRealTime = false
  val adaptor =
    AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput = input,
      sourcePixelBufferAttributes =
        mapOf(
          PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt(),
          PIXEL_BUFFER_WIDTH_KEY to width,
          PIXEL_BUFFER_HEIGHT_KEY to height,
        ),
    )
  writer.addInput(input)
  check(writer.startWriting()) { "the writer refused to start: ${writer.error?.localizedDescription}" }
  writer.startSessionAtSourceTime(kCMTimeZero.readValue())
  repeat(frames) { index ->
    while (!input.readyForMoreMediaData) NSThread.sleepForTimeInterval(READY_POLL_SECONDS)
    val buffer = barsBuffer(adaptor, width, height, bars)
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
  width: Int,
  height: Int,
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
    for (y in 0 until height) {
      for (x in 0 until width) {
        val (red, green, blue) = bars[x * bars.size / width]
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

// From the box's name, its four byte colour type and then the primaries as a big endian 16 bit code point.
private const val COLR_PRIMARIES_OFFSET = 8
private const val READY_POLL_SECONDS = 0.005
