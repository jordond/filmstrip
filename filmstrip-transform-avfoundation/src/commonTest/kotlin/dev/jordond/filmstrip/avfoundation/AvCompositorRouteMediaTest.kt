package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.compositorOutputAttributes
import dev.jordond.filmstrip.avfoundation.internal.composition.compositorSourceAttributes
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.internal.writer.AvDestination
import dev.jordond.filmstrip.avfoundation.internal.writer.WriterRun
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAsynchronousVideoCompositionRequest
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCompositingProtocol
import platform.AVFoundation.AVVideoCompositionInstructionProtocol
import platform.AVFoundation.AVVideoCompositionRenderContext
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_2100_HLG
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIImageColorSpace
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVBufferSetAttachment
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVAttachmentMode_ShouldPropagate
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSFileManager
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.numberWithInt
import platform.darwin.NSObject
import platform.darwin.NSObjectMeta
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins what AVFoundation does with a Kotlin class handed to it as a custom `AVVideoCompositing`.
 *
 * The class object goes over through `interpretObjCPointer` on its companion's `objcPtr`. AVFoundation calls alloc and
 * init on it, which raises unless the constructor is marked `@OverrideInit`, and the Kotlin initialisers then run. An
 * instruction reaches each request as the same Kotlin object, so a compositor reads its plan from there.
 *
 * Measured through the real writer on macOS 26 and the iOS 27 simulator, with the source's codes read raw:
 * - A file's first frames carry the same codes, on PQ and HLG alike.
 * - A timeline gap asks for a frame whose source is nil, and the reader carries on past it.
 * - `sourceTrackIDForFrameTiming` still asks for a frame every slot of a held tail, each with a source.
 * - The writer colour matches a frame unless the frame says otherwise, so a frame written to a BT.709 file carries
 * BT.709 attachments. Without them an HLG patch wrote luma 62, not its own 102.
 * - A compositor that takes HDR frames and conforms colour itself, on a composition naming no colour, gets each clip's
 * frames with the codes and tags its own file carries. Take either property away and frames arrive converted, and frame
 * 0 can differ from frame 1 on macOS.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class AvCompositorRouteMediaTest {
  @Test
  fun `avfoundation builds a kotlin compositor and hands it the instruction`() =
    runTest(timeout = TIMEOUT) {
      val path = withContext(Dispatchers.Default) { written(wholeClip(), "built") }

      withClue("the initialisers did not run under AVFoundation's alloc and init") {
        (RouteLog.initialised.load() > 0).shouldBeTrue()
      }
      val requests = RouteLog.requests.load()
      requests.size shouldBe videoSamplesOf(path).size
      requests.all { it.sameInstruction }.shouldBeTrue()
      remove(path)
    }

  @Test
  fun `the first frames of a written file carry the same codes`() =
    runTest(timeout = TIMEOUT) {
      val path = withContext(Dispatchers.Default) { written(wholeClip(), "frames") }

      val luma = lumaOf(path, frames = FRAME_FOUR + 1)
      withClue("frames 0 and 1 of $luma") { luma[1] shouldBe luma[0] }
      withClue("frames 0 and 4 of $luma") { luma[FRAME_FOUR] shouldBe luma[0] }
      withClue("the patch read as nothing at all in $luma") { (luma[0] > BLACK_LUMA).shouldBeTrue() }
      remove(path)
    }

  @Test
  fun `a gap asks for a frame with no source and the run carries on`() =
    runTest(timeout = TIMEOUT) {
      val gapped =
        compositionOf {
          track {
            clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) { trim(TimeRange.of(Duration.ZERO, HALF_SECOND)) }
            startAt(GAP)
          }
        }

      val path = withContext(Dispatchers.Default) { written(gapped, "gap") }

      val requests = RouteLog.requests.load().sortedBy { it.at }
      requests.size shouldBe GAP_SLOTS + HALF_SECOND_SLOTS
      requests.take(GAP_SLOTS).all { !it.hadSource }.shouldBeTrue()
      requests.drop(GAP_SLOTS).all { it.hadSource }.shouldBeTrue()
      val luma = lumaOf(path, frames = GAP_SLOTS + 1)
      luma.first() shouldBe BLACK_LUMA
      (luma.last() > BLACK_LUMA).shouldBeTrue()
      remove(path)
    }

  @Test
  fun `a held tail asks for a frame every slot with a source`() =
    runTest(timeout = TIMEOUT) {
      val held =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) { trim(TimeRange.of(Duration.ZERO, HALF_SECOND)) }
          track(TrackContent.Audio) {
            clip(MediaSource.of(fixture("tone_bed_880.mp4"))) { trim(TimeRange.of(Duration.ZERO, 1.seconds)) }
          }
        }

      val path = withContext(Dispatchers.Default) { written(held, "tail", holdsTail = true) }

      val requests = RouteLog.requests.load()
      requests.size shouldBe HALF_SECOND_SLOTS * 2
      requests.all { it.hadSource }.shouldBeTrue()
      videoSamplesOf(path).size shouldBe HALF_SECOND_SLOTS * 2
      remove(path)
    }

  @Test
  fun `each clip arrives with its own tags`() =
    runTest(timeout = TIMEOUT) {
      val mixed =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(TimeRange.of(Duration.ZERO, HALF_SECOND)) }
          clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) { trim(TimeRange.of(Duration.ZERO, HALF_SECOND)) }
          clip(MediaSource.of(fixture("apple_export_long.mp4"))) { trim(TimeRange.of(Duration.ZERO, HALF_SECOND)) }
        }

      withContext(Dispatchers.Default) { written(mixed, "own-tags") }

      val transfers =
        RouteLog.requests
          .load()
          .sortedBy { it.at }
          .map { it.transfer }
      transfers.take(HALF_SECOND_SLOTS).toSet() shouldBe setOf(AVVideoTransferFunction_ITU_R_2100_HLG)
      transfers.drop(HALF_SECOND_SLOTS).take(HALF_SECOND_SLOTS).toSet() shouldBe
        setOf(AVVideoTransferFunction_SMPTE_ST_2084_PQ)
      transfers.drop(HALF_SECOND_SLOTS * 2).toSet() shouldBe setOf(AVVideoTransferFunction_ITU_R_709_2)
    }

  private fun wholeClip() = compositionOf { clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  /**
   * Exports [composition] through the real writer on the custom route, and answers the path of the file it wrote.
   */
  private suspend fun written(
    composition: EditComposition,
    name: String,
    holdsTail: Boolean = false,
  ): String {
    RouteLog.reset()
    val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
    val resolved =
      when (val result = avFoundationExportEngine(components).resolve(composition, SPEC)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    val laid = resolved.toAvComposition()
    val graph =
      AvComposition(
        composition = laid.composition,
        videoComposition = routed(resolved, laid, holdsTail),
        audioMix = laid.audioMix,
        encodesHdr = false,
        transfer = null,
        laidSpans = emptyList(),
        chain = null,
        drawnByCompositor = false,
        timedByGrid = false,
      )
    val path = NSTemporaryDirectory() + "filmstrip-route-$name.mp4"
    val destination = AvDestination(NSURL.fileURLWithPath(path), path, MediaSink.Path(path))
    WriterRun(chainedProber(components), resolved, graph, destination)
      .run()
      .toList()
      .last()
      .shouldBeInstanceOf<ExportStatus.Success>()
    return path
  }

  private fun routed(
    resolved: ResolvedComposition,
    graph: AvComposition,
    holdsTail: Boolean,
  ): AVMutableVideoComposition {
    val track = graph.composition.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
    val instruction =
      RouteInstruction(CMTimeRangeMake(start = CMTimeMake(0, TIMESCALE), graph.composition.duration), track.trackID)
    RouteLog.instruction.store(instruction)
    val composition = AVMutableVideoComposition.videoComposition()
    composition.setCustomVideoCompositorClass(
      interpretObjCPointer<AVVideoCompositingProtocol>(RouteCompositor.objcPtr()),
    )
    composition.setRenderSize(
      CGSizeMake(
        resolved.output.size.width
          .toDouble(),
        resolved.output.size.height
          .toDouble(),
      ),
    )
    composition.setFrameDuration(CMTimeMake(value = 1, timescale = resolved.output.frameRate))
    composition.setInstructions(listOf(instruction))
    if (holdsTail) composition.setSourceTrackIDForFrameTiming(kCMPersistentTrackID_Invalid)
    return composition
  }

  /**
   * The luma of the centre of the first [frames] frames of the file at [path], no colour conversion on the way.
   */
  private fun lumaOf(
    path: String,
    frames: Int,
  ): List<Int> {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
    val output =
      AVAssetReaderTrackOutput(
        track = track,
        outputSettings = mapOf(PIXEL_FORMAT_KEY to kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange.toInt()),
      )
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for $path")
    reader.addOutput(output)
    reader.startReading().shouldBeTrue()
    val read = mutableListOf<Int>()
    while (read.size < frames) {
      val sample = output.copyNextSampleBuffer() ?: break
      CMSampleBufferGetImageBuffer(sample)?.let { read += lumaAtCentre(it) }
      CFRelease(sample)
    }
    reader.cancelReading()
    read.size shouldBe frames
    return read
  }

  private companion object {
    const val FRAME_FOUR = 4
    const val BLACK_LUMA = 16
    const val TIMESCALE = 600
    const val GAP_SLOTS = 9
    const val HALF_SECOND_SLOTS = 15
    val GAP = 300.milliseconds
    val HALF_SECOND = 500.milliseconds
    val TIMEOUT = 3.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}

/**
 * One request as the compositor saw it.
 */
private class RouteRequest(
  val at: Duration,
  val hadSource: Boolean,
  val sameInstruction: Boolean,
  val transfer: Any?,
)

@OptIn(ExperimentalAtomicApi::class)
private object RouteLog {
  val initialised = AtomicInt(0)
  val instruction = AtomicReference<RouteInstruction?>(null)
  val requests = AtomicReference<List<RouteRequest>>(emptyList())

  fun reset() {
    initialised.store(0)
    requests.store(emptyList())
  }

  fun record(request: RouteRequest) {
    while (true) {
      val held = requests.load()
      if (requests.compareAndSet(held, held + request)) return
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private class RouteInstruction(
  private val range: CValue<CMTimeRange>,
  private val track: Int,
) : NSObject(),
  AVVideoCompositionInstructionProtocol {
  override fun timeRange(): CValue<CMTimeRange> = range

  override fun enablePostProcessing(): Boolean = false

  override fun containsTweening(): Boolean = true

  override fun requiredSourceTrackIDs(): List<*> = listOf(NSNumber.numberWithInt(track))

  override fun passthroughTrackID(): Int = kCMPersistentTrackID_Invalid
}

/**
 * Writes each source frame's codes out as they came, black where a request has no source.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, BetaInteropApi::class)
private class RouteCompositor
  @OverrideInit
  constructor() :
  NSObject(),
    AVVideoCompositingProtocol {
    private val context = CIContext()

    init {
      RouteLog.initialised.incrementAndFetch()
    }

    override fun sourcePixelBufferAttributes(): Map<Any?, *> = compositorSourceAttributes()

    override fun requiredPixelBufferAttributesForRenderContext(): Map<Any?, *> = compositorOutputAttributes()

    override fun supportsHDRSourceFrames(): Boolean = true

    override fun canConformColorOfSourceFrames(): Boolean = true

    override fun renderContextChanged(newRenderContext: AVVideoCompositionRenderContext) = Unit

    override fun startVideoCompositionRequest(asyncVideoCompositionRequest: AVAsynchronousVideoCompositionRequest) {
      autoreleasepool {
        val request = asyncVideoCompositionRequest
        val track = (request.sourceTrackIDs().firstOrNull() as? NSNumber)?.intValue
        val source = track?.let { request.sourceFrameByTrackID(it) }
        RouteLog.record(
          RouteRequest(
            at = request.compositionTime.toDuration(),
            hadSource = source != null,
            sameInstruction = request.videoCompositionInstruction() === RouteLog.instruction.load(),
            transfer =
              source?.let {
                CFBridgingRelease(
                  CVBufferCopyAttachment(it, kCVImageBufferTransferFunctionKey, null),
                )
              },
          ),
        )

        val out = request.renderContext.newPixelBuffer() ?: error("no output buffer")
        val image =
          if (source != null) {
            CIImage.imageWithCVPixelBuffer(source, options = mapOf(kCIImageColorSpace to NSNull()))
          } else {
            CIImage.imageWithColor(CIColor.blackColor).imageByCroppingToRect(CGRectMake(0.0, 0.0, 1280.0, 720.0))
          }
        context.render(image, toCVPixelBuffer = out, bounds = image.extent, colorSpace = null)
        CVBufferSetAttachment(
          out,
          kCVImageBufferColorPrimariesKey,
          kCVImageBufferColorPrimaries_ITU_R_709_2,
          kCVAttachmentMode_ShouldPropagate,
        )
        CVBufferSetAttachment(
          out,
          kCVImageBufferTransferFunctionKey,
          kCVImageBufferTransferFunction_ITU_R_709_2,
          kCVAttachmentMode_ShouldPropagate,
        )
        CVBufferSetAttachment(
          out,
          kCVImageBufferYCbCrMatrixKey,
          kCVImageBufferYCbCrMatrix_ITU_R_709_2,
          kCVAttachmentMode_ShouldPropagate,
        )
        request.finishWithComposedVideoFrame(out)
        CVPixelBufferRelease(out)
      }
    }

    companion object : NSObjectMeta()
  }

@OptIn(ExperimentalForeignApi::class)
private fun lumaAtCentre(buffer: CVPixelBufferRef): Int {
  CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
  try {
    val plane = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)?.reinterpret<UByteVar>() ?: error("no luma plane")
    val row = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
    val x = CVPixelBufferGetWidth(buffer).toInt() / 2
    val y = CVPixelBufferGetHeight(buffer).toInt() / 2
    return plane[y * row + x].toInt()
  } finally {
    CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
  }
}
