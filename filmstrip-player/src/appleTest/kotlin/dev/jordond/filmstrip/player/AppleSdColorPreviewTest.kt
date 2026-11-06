package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PreviewPlan
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.addOutput
import platform.AVFoundation.pause
import platform.AVFoundation.seekToTime
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.videoComposition
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithUnsignedInt
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * An SD clip off every surface that reads one, each held to the colour `sdSourceDisplayed` says the clip displays as.
 *
 * The clip carries no tags and sits in the sizes VideoToolbox guesses as BT.601 and SMPTE C primaries, on both OSes.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleSdColorPreviewTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `an export draws the colour an sd clip displays as`() =
    contractTest(timeout = TIMEOUT) {
      withClip("export") { path, shown ->
        assertShows(appleExportFrame(editOf(path), POSITION), shown, "the export")
      }
    }

  @Test
  fun `a thumbnail draws the colour an sd clip displays as`() =
    contractTest(timeout = TIMEOUT) {
      withClip("thumbnail") { path, shown ->
        val edit = editOf(path)
        val request = ThumbnailRequest(edit, POSITION, CLIP_HEIGHT, REVISION, precise = true)
        val source = createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the edit")
        val thumbnail = source.thumbnailOf(request)
        try {
          val frame = TestFrame(thumbnail.image.toRgba8888(), Size(thumbnail.image.widthPx, thumbnail.image.heightPx))
          assertShows(frame, shown, "the thumbnail")
        } finally {
          thumbnail.image.close()
        }
      }
    }

  @Test
  fun `a frame read draws the colour an sd clip displays as`() =
    contractTest(timeout = TIMEOUT) { scope ->
      withClip("readback") { path, shown ->
        val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
        withEngine(engine) { recorder ->
          engine.awaitComposition(editOf(path)).shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
          assertShows(engine.readback.awaitFrame(POSITION).asTestFrame(), shown, "the frame read")
        }
      }
    }

  // The iOS 27.0 simulator runtime fails an item with a video composition and a video output with -12784, where 26.5
  // and 27.2 reach ready. Only macOS and an iPhone read the pixels here.
  @IgnoreOnSimulator
  @Test
  fun `a player draws the colour an sd clip displays as`() =
    contractTest(timeout = TIMEOUT) {
      withClip("player") { path, shown ->
        val plan =
          AvPreviewPlanner(CONTRACT_COMPONENTS)
            .plan(editOf(path), PreviewQualityPolicy.Full, ExportSpec())
            .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
            .plan
        val laid = plan.resolved.toAvComposition()
        val output =
          AVPlayerItemVideoOutput(
            pixelBufferAttributes =
              NSDictionary.dictionaryWithObject(
                NSNumber.numberWithUnsignedInt(kCVPixelFormatType_32BGRA),
                forKey = NSString.create(string = "PixelFormatType"),
              ),
          )
        val item =
          AVPlayerItem(asset = laid.composition).apply {
            videoComposition = laid.videoComposition
            addOutput(output as AVPlayerItemOutput)
          }
        val player = AVPlayer(playerItem = item)
        awaitContract("the item to be ready") { item.status == AVPlayerItemStatusReadyToPlay }
        val time = POSITION.toCMTime()
        val exact = Duration.ZERO.toCMTime()
        awaitStep("a seek to $POSITION") {
          suspendCancellableCoroutine { continuation ->
            item.seekToTime(time, toleranceBefore = exact, toleranceAfter = exact) { continuation.resume(Unit) }
          }
        }
        awaitContract("a pixel buffer at $POSITION") { output.hasNewPixelBufferForItemTime(time) }
        val buffer =
          output.copyPixelBufferForItemTime(time, itemTimeForDisplay = null) ?: fail("no buffer at $POSITION")
        try {
          assertShows(buffer.bgraToTestFrame(), shown, "the player")
        } finally {
          CVPixelBufferRelease(buffer)
        }
        player.pause()
      }
    }

  private suspend fun withClip(
    name: String,
    check: suspend (path: String, shown: DoubleArray) -> Unit,
  ) {
    val path = NSTemporaryDirectory() + "filmstrip-sd-preview-$name.mp4"
    writeClip(path)
    try {
      check(path, displayedOf(path))
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  // What a reader with no composition hands back is the clip's own colour, so the expected read starts from it.
  private fun displayedOf(path: String): DoubleArray {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
    val output =
      AVAssetReaderTrackOutput(
        track = track,
        outputSettings =
          NSDictionary.dictionaryWithObject(
            NSNumber.numberWithUnsignedInt(kCVPixelFormatType_32BGRA),
            forKey = NSString.create(string = "PixelFormatType"),
          ),
      )
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: fail("no reader for $path")
    reader.addOutput(output)
    reader.startReading().shouldBeTrue()
    try {
      val sample = output.copyNextSampleBuffer() ?: fail("no frame in $path")
      try {
        val read =
          (
            CMSampleBufferGetImageBuffer(
              sample,
            ) ?: fail("no image in $path")
          ).bgraToTestFrame().codesAt(HALF, HALF)
        val source = doubleArrayOf(read.red / MAX_CODE, read.green / MAX_CODE, read.blue / MAX_CODE)
        return sdSourceDisplayed(source, SMPTE_C_PRIMARIES)
      } finally {
        CFRelease(sample)
      }
    } finally {
      reader.cancelReading()
    }
  }

  private fun assertShows(
    frame: TestFrame,
    shown: DoubleArray,
    label: String,
  ) {
    val read = frame.codesAt(HALF, HALF)
    val expected = shown.map { it * MAX_CODE }
    withClue("$label read $read against $expected") {
      val differences = listOf(read.red - expected[0], read.green - expected[1], read.blue - expected[2])
      differences.all { abs(it) <= SD_CODES }.shouldBeTrue()
    }
  }

  private fun editOf(path: String): EditComposition =
    EditComposition(tracks = listOf(Track(listOf(Clip(MediaSource.of(path), TimeRange.of(Duration.ZERO, CLIP))))))

  private fun writeClip(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    val writer =
      AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4, error = null)
        ?: fail("no writer for $path")
    val input =
      AVAssetWriterInput(
        mediaType = AVMediaTypeVideo,
        outputSettings =
          mapOf(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoWidthKey to CLIP_WIDTH,
            AVVideoHeightKey to CLIP_HEIGHT,
          ),
      )
    input.expectsMediaDataInRealTime = false
    val adaptor =
      AVAssetWriterInputPixelBufferAdaptor(
        assetWriterInput = input,
        sourcePixelBufferAttributes =
          mapOf(
            "PixelFormatType" to kCVPixelFormatType_32BGRA.toInt(),
            "Width" to CLIP_WIDTH,
            "Height" to CLIP_HEIGHT,
          ),
      )
    writer.addInput(input)
    writer.startWriting().shouldBeTrue()
    writer.startSessionAtSourceTime(kCMTimeZero.readValue())
    repeat(FRAMES) { index ->
      while (!input.readyForMoreMediaData) NSThread.sleepForTimeInterval(READY_POLL_SECONDS)
      val buffer = adaptor.solidBuffer()
      adaptor.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(index.toLong(), FRAME_RATE)).shouldBeTrue()
      CVPixelBufferRelease(buffer)
    }
    input.markAsFinished()
    val done = dispatch_semaphore_create(0)
    writer.finishWritingWithCompletionHandler { dispatch_semaphore_signal(done) }
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    withClue("the clip did not finish: ${writer.error?.localizedDescription}") { (writer.error == null).shouldBeTrue() }
  }

  private fun AVAssetWriterInputPixelBufferAdaptor.solidBuffer() =
    memScoped {
      val out = alloc<CVPixelBufferRefVar>()
      CVPixelBufferPoolCreatePixelBuffer(null, pixelBufferPool ?: fail("no buffer pool"), out.ptr)
      val buffer = out.value ?: fail("the pool gave no buffer")
      CVPixelBufferLockBaseAddress(buffer, 0u)
      val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<UByteVar>() ?: fail("no base address")
      val row = CVPixelBufferGetBytesPerRow(buffer).toInt()
      for (y in 0 until CLIP_HEIGHT) {
        for (x in 0 until CLIP_WIDTH) {
          val offset = y * row + x * BGRA_BYTES
          base[offset] = BLUE.toUByte()
          base[offset + 1] = GREEN.toUByte()
          base[offset + 2] = RED.toUByte()
          base[offset + 3] = UByte.MAX_VALUE
        }
      }
      CVPixelBufferUnlockBaseAddress(buffer, 0u)
      buffer
    }

  private suspend fun ThumbnailSource.thumbnailOf(request: ThumbnailRequest): ThumbnailResult.Success =
    awaitStep("a thumbnail at ${request.position}") {
      suspendCancellableCoroutine { continuation ->
        val handle =
          requestThumbnail(request) { result ->
            when (result) {
              is ThumbnailResult.Success -> continuation.resume(result)
              is ThumbnailResult.Failure -> continuation.resume(fail("the thumbnail failed: ${result.error.message}"))
            }
          }
        continuation.invokeOnCancellation { handle.cancel() }
      }
    }

  private companion object {
    const val MAX_CODE = 255.0
    const val HALF = 0.5f
    const val CLIP_WIDTH = 640
    const val CLIP_HEIGHT = 360
    const val FRAMES = 15
    const val FRAME_RATE = 30
    const val BGRA_BYTES = 4
    const val READY_POLL_SECONDS = 0.005
    const val REVISION = 400L

    // A mid range colour, one primaries moving on BT.709 shows up on.
    const val RED = 200
    const val GREEN = 90
    const val BLUE = 110

    // What a read and the shared function differ by, one 8-bit chroma step of the clip's H.264.
    const val SD_CODES = 2.0

    val POSITION = 100.milliseconds
    val CLIP = 400.milliseconds
    val TIMEOUT = 3.minutes
  }
}
