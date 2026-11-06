package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
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
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addOutput
import platform.AVFoundation.pause
import platform.AVFoundation.seekToTime
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.videoComposition
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.CoreImage.kCIImageColorSpace
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileManager
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithUnsignedInt
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The frame a preview shows of a tone mapped edit, off every surface that reads one.
 *
 * Two clips of differing grades are what make `HdrMode.Auto` tone map on any device, so the edit here takes the same
 * route on macOS, where a lone HDR clip keeps its grade, as on the simulator. Each surface is held to the frame the
 * export renders at that time and to the shared curve, frame 0 and a later frame alike.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleToneMapPreviewTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a thumbnail of frame 0 reads what the export writes`() =
    contractTest(timeout = TIMEOUT) {
      listOf(Duration.ZERO, LATER).forEach { position ->
        val edit = toneMappedEdit()
        val request = ThumbnailRequest(edit, position, THUMBNAIL_HEIGHT, REVISION, precise = true)
        val source = createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the edit")
        val thumbnail = source.thumbnailOf(request)
        try {
          thumbnail.presentationTime shouldBe position
          val frame = TestFrame(thumbnail.image.toRgba8888(), Size(thumbnail.image.widthPx, thumbnail.image.heightPx))
          assertOnTheCurve(frame, edit, position, "the thumbnail at $position")
        } finally {
          thumbnail.image.close()
        }
      }
    }

  @Test
  fun `a frame read at frame 0 reads what the export writes`() =
    contractTest(timeout = TIMEOUT) { scope ->
      val edit = toneMappedEdit()
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
        listOf(Duration.ZERO, LATER).forEach { position ->
          val read = engine.readback.awaitFrame(position)
          read.presentationTime shouldBe position
          assertOnTheCurve(read.asTestFrame(), edit, position, "the frame read at $position")
        }
      }
    }

  @Test
  fun `a preview seek to frame 0 shows what the export writes`() =
    contractTest(timeout = TIMEOUT) {
      val edit = toneMappedEdit()
      val plan =
        AvPreviewPlanner(CONTRACT_COMPONENTS)
          .plan(edit, PreviewQualityPolicy.Full)
          .shouldBeInstanceOf<PlanResult.Ready<PreviewPlan>>()
          .plan
      val laid = plan.resolved.toAvComposition()
      // The simulator fails any item carrying a video output with -12784, whatever renders it, the handler included. So
      // it proves the item plays and seeks through the compositor, and macOS and an iPhone read the pixels too.
      val output = if (runsOnSimulator()) null else bgraOutput()
      val item =
        AVPlayerItem(asset = laid.composition).apply {
          videoComposition = laid.videoComposition
          output?.let { addOutput(it as AVPlayerItemOutput) }
        }
      val player = AVPlayer(playerItem = item)
      awaitContract("the item to be ready") { item.status == AVPlayerItemStatusReadyToPlay }
      listOf(Duration.ZERO, LATER).forEach { position ->
        val time = position.toCMTime()
        val exact = Duration.ZERO.toCMTime()
        awaitStep("a seek to $position") {
          suspendCancellableCoroutine { continuation ->
            item.seekToTime(time, toleranceBefore = exact, toleranceAfter = exact) { continuation.resume(Unit) }
          }
        }
        item.status shouldBe AVPlayerItemStatusReadyToPlay
        if (output != null) {
          awaitContract("a pixel buffer at $position") { output.hasNewPixelBufferForItemTime(time) }
          val shown =
            output.copyPixelBufferForItemTime(time, itemTimeForDisplay = null) ?: fail("no buffer at $position")
          try {
            assertOnTheCurve(shown.bgraToTestFrame(), edit, position, "the player at $position")
          } finally {
            CVPixelBufferRelease(shown)
          }
        }
      }
      player.pause()
    }

  private fun runsOnSimulator(): Boolean = NSProcessInfo.processInfo.environment[SIMULATOR_KEY] != null

  private fun bgraOutput(): AVPlayerItemVideoOutput =
    AVPlayerItemVideoOutput(
      pixelBufferAttributes =
        NSDictionary.dictionaryWithObject(
          NSNumber.numberWithUnsignedInt(kCVPixelFormatType_32BGRA),
          forKey = NSString.create(string = PIXEL_FORMAT_KEY),
        ),
    )

  private suspend fun assertOnTheCurve(
    frame: TestFrame,
    edit: EditComposition,
    position: Duration,
    label: String,
  ) {
    val read = frame.codesAt(HALF, HALF)
    val export = appleExportFrame(edit, position).codesAt(HALF, HALF)
    assertWithin(
      read,
      export.red.toDouble(),
      export.green.toDouble(),
      export.blue.toDouble(),
      "$label against the export",
    )
    val curve = toneMappedSdr(HdrTransfer.Pq, signalAtCentreOf(hdrClip()))
    assertWithin(read, curve.r * MAX_CODE, curve.g * MAX_CODE, curve.b * MAX_CODE, "$label against the curve")
  }

  private fun assertWithin(
    read: Codes,
    red: Double,
    green: Double,
    blue: Double,
    label: String,
  ) {
    withClue("$label read $read against ($red, $green, $blue)") {
      val differences = listOf(read.red - red, read.green - green, read.blue - blue)
      differences.all { abs(it) <= CURVE_CODES }.shouldBeTrue()
    }
  }

  private fun toneMappedEdit(): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(MediaSource.of(hdrClip()), TimeRange.of(Duration.ZERO, CLIP)),
              Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP)),
            ),
          ),
        ),
    )

  private fun hdrClip(): String {
    val directory = NSProcessInfo.processInfo.environment[FIXTURES] as? String ?: fail("$FIXTURES was not set.")
    val path = "$directory/$HDR_FIXTURE"
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) fail("The fixture $path was not downloaded.")
    return path
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
    const val HALF = 0.5f
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val SIMULATOR_KEY = "SIMULATOR_UDID"
    const val HDR_FIXTURE = "android_export_hdr.mp4"
    const val MAX_CODE = 255.0
    const val THUMBNAIL_HEIGHT = 720
    const val REVISION = 300L

    // What every backend's tone map meets against the shared curve, in 8-bit codes.
    const val CURVE_CODES = 2.0
    val CLIP = 500.milliseconds
    val LATER = 200.milliseconds
    val TIMEOUT = 3.minutes
  }
}

/**
 * The signal the first frame of the HDR file at [path] carries at its centre, read the way the tone map's head reads
 * it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun signalAtCentreOf(path: String): Rgb {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track = asset.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
  val output =
    AVAssetReaderTrackOutput(
      track = track,
      outputSettings = mapOf(PIXEL_FORMAT_KEY to kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt()),
    )
  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for $path")
  reader.addOutput(output)
  reader.startReading().shouldBeTrue()
  val sample = output.copyNextSampleBuffer() ?: error("no sample in $path")
  try {
    val buffer = CMSampleBufferGetImageBuffer(sample) ?: error("no image buffer in $path")
    val image = CIImage.imageWithCVPixelBuffer(buffer, options = mapOf(kCIImageColorSpace to NSNull()))
    val texel = FloatArray(TEXEL)
    val (x, y) = image.extent.useContents { size.width / 2.0 to size.height / 2.0 }
    texel.usePinned { pinned ->
      CIContext().render(
        image,
        toBitmap = pinned.addressOf(0),
        rowBytes = (TEXEL * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(x, y, 1.0, 1.0),
        format = kCIFormatRGBAf,
        colorSpace = null,
      )
    }
    return Rgb(texel[0].toDouble(), texel[1].toDouble(), texel[2].toDouble())
  } finally {
    CFRelease(sample)
    reader.cancelReading()
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun CVPixelBufferRef.bgraToTestFrame(): TestFrame {
  CVPixelBufferLockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
  try {
    val width = CVPixelBufferGetWidth(this).toInt()
    val height = CVPixelBufferGetHeight(this).toInt()
    val row = CVPixelBufferGetBytesPerRow(this).toInt()
    val base = CVPixelBufferGetBaseAddress(this)?.reinterpret<ByteVar>() ?: error("no base address")
    val rgba = ByteArray(width * height * RGBA_BYTES)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val from = y * row + x * RGBA_BYTES
        val to = (y * width + x) * RGBA_BYTES
        rgba[to] = base[from + 2]
        rgba[to + 1] = base[from + 1]
        rgba[to + 2] = base[from]
        rgba[to + 3] = base[from + 3]
      }
    }
    return TestFrame(rgba, Size(width, height))
  } finally {
    CVPixelBufferUnlockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
  }
}

private const val TEXEL = 4
private const val RGBA_BYTES = 4
