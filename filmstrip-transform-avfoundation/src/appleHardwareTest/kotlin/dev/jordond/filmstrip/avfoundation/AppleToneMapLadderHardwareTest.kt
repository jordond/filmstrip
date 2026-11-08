package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_HEIGHT_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_WIDTH_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.color.BT2020_CB_SCALE
import dev.jordond.filmstrip.color.BT2020_CR_SCALE
import dev.jordond.filmstrip.color.BT2020_LUMA_B
import dev.jordond.filmstrip.color.BT2020_LUMA_G
import dev.jordond.filmstrip.color.BT2020_LUMA_R
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeHEVC
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_2020
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_2100_HLG
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_2020
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVBufferSetAttachment
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVAttachmentMode_ShouldPropagate
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_2020
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_2100_HLG
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_2020
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A ladder of known mid-range patches written with `AVAssetWriter`, exported tone mapped and read back from the file.
 *
 * Every patch is held to `toneMappedSdr` on frames 0, 1 and a later one. The ladder takes a hardware ten-bit HEVC
 * encoder, which is why this runs on macOS and an iPhone and not on the simulator. The patches sit on every piece of
 * the PQ knee and both sides of the HLG crossover, greys and colours alike, and none saturates every channel.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleToneMapLadderHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `the pq ladder lands on the shared curve in the written file`() = assertLadder(HdrTransfer.Pq, PQ_LADDER)

  @Test
  fun `the hlg ladder lands on the shared curve in the written file`() = assertLadder(HdrTransfer.Hlg, HLG_LADDER)

  private fun assertLadder(
    transfer: HdrTransfer,
    ladder: List<Rgb>,
  ) = runTest(timeout = TIMEOUT) {
    val source = NSTemporaryDirectory() + "filmstrip-ladder-$transfer.mov"
    val output = NSTemporaryDirectory() + "filmstrip-ladder-$transfer.mp4"
    withContext(Dispatchers.Default) { writeLadder(source, transfer, ladder) }

    val status =
      withContext(Dispatchers.Default) {
        filmstrip
          .export(
            compositionOf { clip(MediaSource.of(source)) },
            ExportSpec(hdr = HdrMode.ToneMapToSdr),
            MediaSink.of(output),
          ).toList()
          .last()
      }
    status.shouldBeInstanceOf<ExportStatus.Success>()

    val frames = codeFramesOf(output, count = LADDER_FRAMES)
    listOf(0, 1, LADDER_FRAMES - 1).forEach { index ->
      ladder.forEachIndexed { patch, signal ->
        val (x, y) = centreOf(patch)
        val expected = toneMappedSdr(transfer, signal)
        withClue("$transfer patch $signal expects $expected, which has to keep a channel off both ends") {
          listOf(expected.r, expected.g, expected.b).any { it in MID_RANGE }.shouldBeTrue()
        }
        val grey = signal.r == signal.g && signal.g == signal.b
        val tolerance = if (grey) TONE_MAP_TOLERANCE_CODES else WRITTEN_COLOUR_CODES
        assertCodes(frames[index].at(x, y), expected, "$transfer frame $index patch $signal", tolerance)
      }
    }
    NSFileManager.defaultManager.removeItemAtPath(source, error = null)
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
  }

  private fun centreOf(patch: Int): Pair<Float, Float> =
    ((patch % COLUMNS + 0.5f) / COLUMNS) to ((patch / COLUMNS + 0.5f) / ROWS)

  private fun writeLadder(
    path: String,
    transfer: HdrTransfer,
    ladder: List<Rgb>,
  ) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    val writer =
      AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(path), fileType = AVFileTypeQuickTimeMovie, error = null)
        ?: error("no writer for $path")
    val input =
      AVAssetWriterInput(
        mediaType = AVMediaTypeVideo,
        outputSettings =
          mapOf(
            AVVideoCodecKey to AVVideoCodecTypeHEVC,
            AVVideoWidthKey to WIDTH,
            AVVideoHeightKey to HEIGHT,
            AVVideoCompressionPropertiesKey to mapOf(AVVideoProfileLevelKey to HEVC_MAIN10),
            AVVideoColorPropertiesKey to
              mapOf(
                AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_2020,
                AVVideoTransferFunctionKey to
                  if (transfer == HdrTransfer.Pq) {
                    AVVideoTransferFunction_SMPTE_ST_2084_PQ
                  } else {
                    AVVideoTransferFunction_ITU_R_2100_HLG
                  },
                AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_2020,
              ),
          ),
      )
    input.expectsMediaDataInRealTime = false
    val adaptor =
      AVAssetWriterInputPixelBufferAdaptor(
        assetWriterInput = input,
        sourcePixelBufferAttributes =
          mapOf(
            PIXEL_FORMAT_KEY to kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt(),
            PIXEL_BUFFER_WIDTH_KEY to WIDTH,
            PIXEL_BUFFER_HEIGHT_KEY to HEIGHT,
          ),
      )
    check(writer.canAddInput(input)) { "the writer would not take a video input" }
    writer.addInput(input)
    check(writer.startWriting()) { "the writer refused to start: ${writer.error?.localizedDescription}" }
    writer.startSessionAtSourceTime(kCMTimeZero.readValue())

    repeat(LADDER_FRAMES) { index ->
      while (!input.readyForMoreMediaData) NSThread.sleepForTimeInterval(READY_POLL_SECONDS)
      val buffer = newBuffer(adaptor)
      buffer.drawLadder(ladder, transfer)
      val appended = adaptor.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(index.toLong(), FRAME_RATE))
      CVPixelBufferRelease(buffer)
      check(appended) { "frame $index was refused: ${writer.error?.localizedDescription}" }
    }

    input.markAsFinished()
    val done = dispatch_semaphore_create(0)
    writer.finishWritingWithCompletionHandler { dispatch_semaphore_signal(done) }
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    check(writer.error == null) { "the ladder did not finish: ${writer.error?.localizedDescription}" }
  }

  private fun newBuffer(adaptor: AVAssetWriterInputPixelBufferAdaptor): CVPixelBufferRef =
    memScoped {
      val out = alloc<CVPixelBufferRefVar>()
      val pool = adaptor.pixelBufferPool ?: error("the adaptor has no buffer pool")
      CVPixelBufferPoolCreatePixelBuffer(null, pool, out.ptr)
      out.value ?: error("the pool gave no buffer")
    }

  private fun CVPixelBufferRef.drawLadder(
    ladder: List<Rgb>,
    transfer: HdrTransfer,
  ) {
    CVBufferSetAttachment(
      this,
      kCVImageBufferColorPrimariesKey,
      kCVImageBufferColorPrimaries_ITU_R_2020,
      kCVAttachmentMode_ShouldPropagate,
    )
    CVBufferSetAttachment(
      this,
      kCVImageBufferTransferFunctionKey,
      if (transfer == HdrTransfer.Pq) {
        kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
      } else {
        kCVImageBufferTransferFunction_ITU_R_2100_HLG
      },
      kCVAttachmentMode_ShouldPropagate,
    )
    CVBufferSetAttachment(
      this,
      kCVImageBufferYCbCrMatrixKey,
      kCVImageBufferYCbCrMatrix_ITU_R_2020,
      kCVAttachmentMode_ShouldPropagate,
    )
    CVPixelBufferLockBaseAddress(this, 0u)
    try {
      val luma = CVPixelBufferGetBaseAddressOfPlane(this, 0u)?.reinterpret<UShortVar>() ?: error("no luma plane")
      val chroma = CVPixelBufferGetBaseAddressOfPlane(this, 1u)?.reinterpret<UShortVar>() ?: error("no chroma plane")
      val lumaRow = CVPixelBufferGetBytesPerRowOfPlane(this, 0u).toInt() / Short.SIZE_BYTES
      val chromaRow = CVPixelBufferGetBytesPerRowOfPlane(this, 1u).toInt() / Short.SIZE_BYTES
      val patchWidth = WIDTH / COLUMNS
      val patchHeight = HEIGHT / ROWS
      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) {
          luma[y * lumaRow + x] = ladder.patchAt(x, y, patchWidth, patchHeight).lumaCode()
        }
      }
      for (y in 0 until HEIGHT / 2) {
        for (x in 0 until WIDTH / 2) {
          val patch = ladder.patchAt(x * 2, y * 2, patchWidth, patchHeight)
          chroma[y * chromaRow + x * 2] = patch.chromaCode(patch.b, BT2020_CB_SCALE)
          chroma[y * chromaRow + x * 2 + 1] = patch.chromaCode(patch.r, BT2020_CR_SCALE)
        }
      }
    } finally {
      CVPixelBufferUnlockBaseAddress(this, 0u)
    }
  }

  private fun List<Rgb>.patchAt(
    x: Int,
    y: Int,
    patchWidth: Int,
    patchHeight: Int,
  ): Rgb = this[((y / patchHeight) * COLUMNS + x / patchWidth).coerceAtMost(lastIndex)]

  private fun Rgb.luma(): Double = r * BT2020_LUMA_R + g * BT2020_LUMA_G + b * BT2020_LUMA_B

  private fun Rgb.lumaCode(): UShort = ((LUMA_FOOT + luma() * LUMA_SPAN).roundToInt() shl CODE_SHIFT).toUShort()

  private fun Rgb.chromaCode(
    channel: Double,
    scale: Float,
  ): UShort = ((CHROMA_ZERO + (channel - luma()) / scale * CHROMA_SPAN).roundToInt() shl CODE_SHIFT).toUShort()

  private companion object {
    // The documented value of kVTProfileLevel_HEVC_Main10_AutoLevel, which is a pointer in Kotlin and not a string.
    const val HEVC_MAIN10 = "HEVC_Main10_AutoLevel"

    const val WIDTH = 1280
    const val HEIGHT = 720
    const val COLUMNS = 5
    const val ROWS = 2
    const val FRAME_RATE = 30
    const val LADDER_FRAMES = 8
    const val READY_POLL_SECONDS = 0.005
    const val LUMA_FOOT = 64.0
    const val LUMA_SPAN = 876.0
    const val CHROMA_ZERO = 512.0
    const val CHROMA_SPAN = 896.0
    const val CODE_SHIFT = 6
    val TIMEOUT = 3.minutes
    val MID_RANGE = 0.05..0.99

    fun nits(
      red: Int,
      green: Int,
      blue: Int,
    ) = Rgb(
      pqSignalFromNits(red.toFloat()).toDouble(),
      pqSignalFromNits(green.toFloat()).toDouble(),
      pqSignalFromNits(blue.toFloat()).toDouble(),
    )

    val PQ_LADDER =
      listOf(
        nits(10, 10, 10),
        nits(50, 50, 50),
        nits(100, 100, 100),
        nits(200, 200, 200),
        nits(300, 300, 300),
        nits(500, 500, 500),
        nits(800, 800, 800),
        nits(300, 500, 100),
        nits(150, 400, 700),
        nits(0, 90, 140),
      )

    val HLG_LADDER =
      listOf(
        Rgb(0.1, 0.1, 0.1),
        Rgb(0.25, 0.25, 0.25),
        Rgb(0.5, 0.5, 0.5),
        Rgb(0.65, 0.65, 0.65),
        Rgb(0.75, 0.75, 0.75),
        Rgb(0.9, 0.9, 0.9),
        Rgb(0.6, 0.3, 0.1),
        Rgb(0.4, 0.7, 0.2),
        Rgb(0.2, 0.3, 0.8),
        Rgb(0.7, 0.5, 0.3),
      )
  }
}
