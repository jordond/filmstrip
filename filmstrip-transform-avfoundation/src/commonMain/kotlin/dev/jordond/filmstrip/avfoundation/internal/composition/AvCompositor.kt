package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.avfoundation.internal.chain.BT709_CODE_SPACE
import dev.jordond.filmstrip.avfoundation.internal.chain.ChainSnapshot
import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesMoved
import dev.jordond.filmstrip.avfoundation.internal.chain.toneMapped
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.media.HdrTransfer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVAsynchronousVideoCompositionRequest
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoCompositingProtocol
import platform.AVFoundation.AVVideoCompositionInstructionProtocol
import platform.AVFoundation.AVVideoCompositionRenderContext
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_2100_HLG
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIImageColorSpace
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVBufferSetAttachment
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVAttachmentMode_ShouldPropagate
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSArray
import platform.Foundation.NSDictionary
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.arrayWithArray
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import platform.Foundation.numberWithInt
import platform.Foundation.numberWithUnsignedInt
import platform.VideoToolbox.VTPixelTransferSessionCreate
import platform.VideoToolbox.VTPixelTransferSessionInvalidate
import platform.VideoToolbox.VTPixelTransferSessionRefVar
import platform.VideoToolbox.VTPixelTransferSessionTransferImage
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTPixelTransferPropertyKey_DestinationColorPrimaries
import platform.VideoToolbox.kVTPixelTransferPropertyKey_DestinationTransferFunction
import platform.VideoToolbox.kVTPixelTransferPropertyKey_DestinationYCbCrMatrix
import platform.darwin.NSObject
import platform.darwin.NSObjectMeta
import kotlin.time.Duration

/**
 * What every frame of a plan drawn by [AvCompositor] is drawn from.
 *
 * AVFoundation builds the compositor itself and hands it nothing of ours, so the instruction carries the chain and
 * reaches each request as this same object.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvCompositorInstruction(
  private val range: CValue<CMTimeRange>,
  private val track: Int,
  val chain: CoreImageChain,
) : NSObject(),
  AVVideoCompositionInstructionProtocol {
  override fun timeRange(): CValue<CMTimeRange> = range

  override fun enablePostProcessing(): Boolean = false

  // Every frame moves on with the clock, even across a held tail where the source does not.
  override fun containsTweening(): Boolean = true

  override fun requiredSourceTrackIDs(): List<*> = listOf(NSNumber.numberWithInt(track))

  override fun passthroughTrackID(): Int = kCMPersistentTrackID_Invalid
}

/**
 * Renders a tone mapped plan and any plan holding an SD clip: the head of each source frame, then the chain.
 *
 * This compositor takes HDR frames and conforms colour itself, and the composition names no colour, so AVFoundation
 * hands over each clip's frames with the codes and tags its own file carries. An HDR clip's codes go through the tone
 * map kernel and then count as BT.709 SDR codes, and an SDR clip's end up as BT.709 codes too, an SD clip's through
 * the shared SD matrices, so the chain sees the same kind of image either way. The result is written in BT.709 codes
 * and tagged BT.709, without which the writer colour matches the frame out of what it thinks the frame is.
 *
 * A request with no source is a timeline gap, and it goes through the chain as a black frame.
 *
 * Nothing may throw out of a request, since this is a callback from Objective-C. What a step throws is latched by
 * [CoreImageChain.drawn], and anything else fails the request.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AvCompositor
  @OverrideInit
  constructor() :
  NSObject(),
    AVVideoCompositingProtocol {
    override fun sourcePixelBufferAttributes(): Map<Any?, *> = compositorSourceAttributes()

    override fun requiredPixelBufferAttributesForRenderContext(): Map<Any?, *> = compositorOutputAttributes()

    override fun supportsHDRSourceFrames(): Boolean = true

    override fun canConformColorOfSourceFrames(): Boolean = true

    override fun renderContextChanged(newRenderContext: AVVideoCompositionRenderContext) = Unit

    override fun startVideoCompositionRequest(asyncVideoCompositionRequest: AVAsynchronousVideoCompositionRequest) {
      autoreleasepool {
        val request = asyncVideoCompositionRequest
        try {
          render(request)
        } catch (broken: Exception) {
          request.finishWithError(
            NSError.errorWithDomain(
              ERROR_DOMAIN,
              code = 0,
              userInfo = errorDetail(broken.message ?: broken.toString()),
            ),
          )
        }
      }
    }

    private fun render(request: AVAsynchronousVideoCompositionRequest) {
      val instruction = request.videoCompositionInstruction() as AvCompositorInstruction
      val chain = instruction.chain
      val state = chain.current
      val track = (request.sourceTrackIDs().firstOrNull() as? NSNumber)?.intValue
      val head = state.head(track?.let(request::sourceFrameByTrackID), request.time())
      val image = chain.drawn(state, head, request.time())

      val output = request.renderContext.newPixelBuffer() ?: error(NO_OUTPUT_BUFFER)
      try {
        val context = chain.renderer ?: error(NO_RENDERER)
        context.renderCodes(image, output, state)
        output.tagBt709()
        request.finishWithComposedVideoFrame(output)
      } finally {
        CVPixelBufferRelease(output)
      }
    }

    companion object : NSObjectMeta()
  }

/**
 * The compositor's class, in the shape AVFoundation's setter is typed as.
 *
 * The setter takes a `Class`, which cinterop types as an instance of the protocol.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun compositorClass(): AVVideoCompositingProtocol = interpretObjCPointer(AvCompositor.objcPtr())

/**
 * The buffers a compositor asks for its sources in, ten-bit for a graded signal, eight-bit for an SDR one and BGRA for
 * a source with an alpha channel, which the biplanar formats cannot carry.
 *
 * A source decoded to one of these reaches the compositor untouched.
 */
internal fun compositorSourceAttributes(): Map<Any?, *> =
  pixelFormats(
    kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
    kCVPixelFormatType_32BGRA,
  )

/**
 * The buffers a compositor renders into, the same format the SDR route reads from its handler.
 */
internal fun compositorOutputAttributes(): Map<Any?, *> = pixelFormats(kCVPixelFormatType_32BGRA)

// A real dictionary, since a bridged Kotlin map is not one once it crosses into Apple's media APIs.
@OptIn(BetaInteropApi::class)
private fun pixelFormats(vararg formats: UInt): Map<Any?, *> =
  NSDictionary.dictionaryWithObject(
    NSArray.arrayWithArray(formats.map(NSNumber::numberWithUnsignedInt)),
    forKey = NSString.create(string = PIXEL_FORMAT_KEY),
  )

// A real dictionary for the same reason as pixelFormats.
@OptIn(BetaInteropApi::class)
private fun errorDetail(message: String): Map<Any?, *> =
  NSDictionary.dictionaryWithObject(
    NSString.create(string = message),
    forKey = NSString.create(string = NSLocalizedDescriptionKey.orEmpty()),
  )

@OptIn(ExperimentalForeignApi::class)
private fun AVAsynchronousVideoCompositionRequest.time() = compositionTime.toDuration()

/**
 * The image the chain starts from at [time], from [frame] or black when there is none.
 *
 * The curve comes from the transfer [frame] itself carries, which reads true whatever format the frame arrived in.
 *
 * @throws IllegalStateException When the clip the plan puts at [time] carries another transfer than [frame] does.
 */
@OptIn(ExperimentalForeignApi::class)
private fun ChainSnapshot.head(
  frame: CVPixelBufferRef?,
  time: Duration,
): CIImage {
  if (frame == null) return blackFrame()
  val carried = frame.hdrTransfer()
  val span = spans.firstOrNull { it.covers(time) }
  span?.let { check(it.sourceTransfer == carried) { transferMismatch(time, it.sourceTransfer, carried) } }
  return when {
    carried != null -> frame.toneMappedHead(carried)
    // The chain swaps the photo in for a still's seed, so the seed's own colour goes nowhere.
    span?.still != null -> frame.rawCodes().asBt709Codes()
    else -> frame.sdrHead(span?.sdColor)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.hdrTransfer(): HdrTransfer? =
  when (tag(kCVImageBufferTransferFunctionKey)) {
    AVVideoTransferFunction_SMPTE_ST_2084_PQ -> HdrTransfer.Pq
    AVVideoTransferFunction_ITU_R_2100_HLG -> HdrTransfer.Hlg
    else -> null
  }

private fun transferMismatch(
  time: Duration,
  planned: HdrTransfer?,
  carried: HdrTransfer?,
): String =
  "The plan puts a clip of ${planned.describe()} at $time, and AVFoundation delivered a frame of ${carried.describe()}."

private fun HdrTransfer?.describe(): String = this?.name ?: "SDR"

@OptIn(ExperimentalForeignApi::class)
private fun ChainSnapshot.blackFrame(): CIImage =
  CIImage
    .imageWithColor(CIColor.blackColor)
    .imageByCroppingToRect(CGRectMake(0.0, 0.0, output.size.width.toDouble(), output.size.height.toDouble()))

/**
 * An SDR clip's frame as BT.709 codes.
 *
 * This composition names no colour, so the frame carries its own tags and Core Image decodes it with its real matrix. A
 * frame of [color] then moves onto BT.709's primaries with the shared matrices. Any other frame not tagged BT.709 goes
 * through VideoToolbox's pixel transfer. A BGRA frame, which is how a source with alpha arrives, already holds RGB
 * codes and keeps its straight alpha attachment, so it is read as it is.
 *
 * @param color The SD colour read off the clip when it was planned, or null when it has none.
 * @throws IllegalStateException When the pixel transfer cannot convert the frame.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CVPixelBufferRef.sdrHead(color: SdSourceColor?): CIImage {
  if (color != null) return rawCodes().sdPrimariesMoved(color).asBt709Codes()
  if (carriesBt709() || CVPixelBufferGetPixelFormatType(this) == kCVPixelFormatType_32BGRA) {
    return rawCodes().asBt709Codes()
  }

  val converted = convertedToBt709()
  try {
    return converted.rawCodes().asBt709Codes()
  } finally {
    CVPixelBufferRelease(converted)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.toneMappedHead(transfer: HdrTransfer): CIImage =
  rawCodes().toneMapped(transfer).asBt709Codes()

@OptIn(ExperimentalForeignApi::class)
private fun CIImage.asBt709Codes(): CIImage =
  imageByColorMatchingColorSpaceToWorkingSpace(BT709_CODE_SPACE) ?: error(NO_CODE_SPACE)

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.tag(key: CFStringRef?): Any? = CFBridgingRelease(CVBufferCopyAttachment(this, key, null))

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.carriesBt709(): Boolean =
  tag(kCVImageBufferColorPrimariesKey) == AVVideoColorPrimaries_ITU_R_709_2 &&
    tag(kCVImageBufferTransferFunctionKey) == AVVideoTransferFunction_ITU_R_709_2 &&
    tag(kCVImageBufferYCbCrMatrixKey) == AVVideoYCbCrMatrix_ITU_R_709_2

/**
 * A new [sdrTransferFormat] buffer holding this frame in BT.709. The caller releases it.
 *
 * @throws IllegalStateException When the transfer cannot do it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.convertedToBt709(): CVPixelBufferRef =
  memScoped {
    val session = alloc<VTPixelTransferSessionRefVar>()
    check(VTPixelTransferSessionCreate(null, session.ptr) == 0) { NO_TRANSFER }
    val transfer = session.value ?: error(NO_TRANSFER)
    try {
      VTSessionSetProperty(
        transfer,
        kVTPixelTransferPropertyKey_DestinationColorPrimaries,
        kCVImageBufferColorPrimaries_ITU_R_709_2,
      )
      VTSessionSetProperty(
        transfer,
        kVTPixelTransferPropertyKey_DestinationTransferFunction,
        kCVImageBufferTransferFunction_ITU_R_709_2,
      )
      VTSessionSetProperty(
        transfer,
        kVTPixelTransferPropertyKey_DestinationYCbCrMatrix,
        kCVImageBufferYCbCrMatrix_ITU_R_709_2,
      )
      val out = alloc<CVPixelBufferRefVar>()
      val created =
        CVPixelBufferCreate(
          null,
          CVPixelBufferGetWidth(this@convertedToBt709),
          CVPixelBufferGetHeight(this@convertedToBt709),
          sdrTransferFormat,
          null,
          out.ptr,
        )
      check(created == 0) { NO_TRANSFER }
      val buffer = out.value ?: error(NO_TRANSFER)
      if (VTPixelTransferSessionTransferImage(transfer, this@convertedToBt709, buffer) != 0) {
        CVPixelBufferRelease(buffer)
        error(NO_TRANSFER)
      }
      buffer
    } finally {
      VTPixelTransferSessionInvalidate(transfer)
      CFRelease(transfer)
    }
  }

// The buffer's RGB signal with no colour space, so nothing moves the codes on the way in.
@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.rawCodes(): CIImage =
  CIImage.imageWithCVPixelBuffer(this, options = mapOf(kCIImageColorSpace to NSNull()))

@OptIn(ExperimentalForeignApi::class)
private fun CIContext.renderCodes(
  image: CIImage,
  output: CVPixelBufferRef,
  state: ChainSnapshot,
) {
  val size = state.output.size
  render(
    image,
    toCVPixelBuffer = output,
    bounds = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()),
    colorSpace = BT709_CODE_SPACE,
  )
}

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.tagBt709() {
  CVBufferSetAttachment(
    this,
    kCVImageBufferColorPrimariesKey,
    kCVImageBufferColorPrimaries_ITU_R_709_2,
    kCVAttachmentMode_ShouldPropagate,
  )
  CVBufferSetAttachment(
    this,
    kCVImageBufferTransferFunctionKey,
    kCVImageBufferTransferFunction_ITU_R_709_2,
    kCVAttachmentMode_ShouldPropagate,
  )
  CVBufferSetAttachment(
    this,
    kCVImageBufferYCbCrMatrixKey,
    kCVImageBufferYCbCrMatrix_ITU_R_709_2,
    kCVAttachmentMode_ShouldPropagate,
  )
}

private const val ERROR_DOMAIN = "dev.jordond.filmstrip.avfoundation.compositor"
private const val NO_OUTPUT_BUFFER = "AVFoundation handed the compositor no buffer to render into."
private const val NO_TRANSFER = "VideoToolbox would not convert an SDR frame to BT.709."
private const val NO_RENDERER = "This process has no Core Image context to render a frame through."
private const val NO_CODE_SPACE = "Core Image would not read a tone mapped frame as BT.709 codes."
