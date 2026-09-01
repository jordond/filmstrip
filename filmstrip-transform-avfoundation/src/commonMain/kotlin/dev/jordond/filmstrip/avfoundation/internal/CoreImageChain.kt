package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.linearDimGain
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.coverScale
import dev.jordond.filmstrip.transform.internal.derivesFromFrame
import dev.jordond.filmstrip.transform.internal.sigmaFor
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.AVFoundation.AVMakeRectWithAspectRatioInsideRect
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectInset
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.CoreImage.kCIInputScaleKey
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * What every frame goes through, from the buffer AVFoundation decoded to the one it encodes.
 *
 * The handler is given a composition time and nothing else, so the clip a frame came from is found
 * by range lookup over [ClipSpan]. A span drawing a still puts that photo in first, in place of the
 * segment its slot was cut from. The clip's container rotation is baked in next, then its own
 * effects run, then composition-level geometry, then the frame is pinned to [OutputFormat.size].
 * Composition-level effects run next, over the picture that frame produced. A named fill colour is
 * only painted into whatever the picture does not cover once those effects have finished, so a grade
 * never reaches a bar or a gap it was not given a colour for. That is the same order Android's
 * pipeline runs, which is what makes a normalised measurement in a composition-level effect a
 * fraction of the same frame on either platform.
 *
 * There is no overlay batching here and no analogue of Android's sampler budget. Core Image fuses a
 * chain of composites into one pass over the frame, whereas media3 spends a whole render pass per
 * `OverlayEffect`, which is the only reason that coalescing exists.
 *
 * A preview shares one `AVVideoComposition` with the export it previews, and reassigning that on a
 * playing `AVPlayerItem` stalls it. Everything a parameters-only edit can change therefore lives
 * behind one reference that [render] re-reads on every frame and [updateParameters] replaces.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public class CoreImageChain(
  resolved: ResolvedComposition,
  spans: List<ClipSpan>,
  encodesHdr: Boolean,
) {
  /**
   * Everything one frame is drawn from, replaced whole rather than field by field.
   *
   * Written from whichever queue an edit arrives on and read from AVFoundation's render queue,
   * which is never that one.
   */
  @Volatile
  private var snapshot: ChainSnapshot = ChainSnapshot(resolved, spans, encodesHdr)

  public val output: OutputFormat get() = snapshot.output

  public val transfer: HdrTransfer? get() = snapshot.transfer

  /**
   * Whether an HDR grade reaches the encoder, which is not always what was asked for.
   */
  public val encodesHdr: Boolean get() = snapshot.encodesHdr

  /**
   * Where each clip sits on the timeline, and what it draws, as of the last swap.
   */
  public val spans: List<ClipSpan> get() = snapshot.spans

  /**
   * Built once and reused for every frame, or null when this process cannot build one at all.
   *
   * A context carries the compiled kernels and their caches, so owning one beats letting
   * AVFoundation make its own. It answers nil where there is no rendering device, which a headless
   * test process is, and AVFoundation renders through one of its own when handed none.
   */
  private val context: CIContext? = renderingContext()

  /**
   * The photos this chain draws, opened once each.
   *
   * Kept outside the snapshot, so a parameters-only edit rebuilding the spans keeps the images it
   * already opened instead of reading them again on the next frame.
   */
  private val stills = CoreImageStills()

  internal val geometryAttributes: Attributes get() = snapshot.geometryAttributes

  /**
   * What a resolver's step threw, or null while nothing has.
   *
   * Written on AVFoundation's render queue and read once the run has stopped.
   */
  @Volatile
  public var failure: ExportError? = null
    private set

  /**
   * Swaps in the effect parameters [resolved] carries, for every frame drawn from here on.
   *
   * Each span keeps the slot it already holds and picks up its clip's new effects, so nothing is
   * laid onto an `AVMutableComposition` again and the `AVVideoComposition` a player is showing
   * stays the object it was handed. A frame already in flight finishes against the parameters it
   * started on.
   *
   * @param resolved The same timeline, planned again with different effect parameters.
   * @throws IllegalArgumentException When [resolved] moves something a caller has already read off
   *   this chain or something the spans were laid out against, which needs the whole graph built
   *   again through [toAvComposition] instead.
   */
  public fun updateParameters(resolved: ResolvedComposition) {
    val current = snapshot
    require(resolved.output == current.output) {
      "A swap cannot change the output format. It was ${current.output}, and ${resolved.output} was given."
    }
    require(resolved.hdrTransfer == current.transfer) {
      "A swap cannot change the transfer function. It was ${current.transfer}, and " +
        "${resolved.hdrTransfer} was given."
    }
    require(resolved.videoClipDurations == current.resolved.videoClipDurations) {
      "A swap keeps the spans it already has, so it cannot change the timeline they cover. It was " +
        "${current.resolved.videoClipDurations}, and ${resolved.videoClipDurations} was given."
    }

    snapshot = ChainSnapshot(resolved, current.spans.respannedOnto(resolved), current.encodesHdr)
  }

  /**
   * Renders one frame and hands it back to AVFoundation.
   *
   * Every frame is wrapped in its own autorelease pool. Core Image allocates heavily per frame and
   * the handler runs on AVFoundation's queue, which drains no pool between requests, so without one
   * a long export climbs until it is killed.
   *
   * A step that throws is caught here. This is a callback from Objective-C, and an exception
   * crossing that boundary terminates the process instead of reaching a caller. The frame is passed
   * through and the reason is kept for the run to report.
   */
  @OptIn(BetaInteropApi::class)
  public fun render(request: AVAsynchronousCIImageFilteringRequest) {
    autoreleasepool {
      // Read once. A swap landing between two reads would draw one edit's spans against another
      // edit's composition, which is a frame neither of them describes.
      val state = snapshot
      val source = request.sourceFrame()
      val frame =
        try {
          compose(state, source, request.compositionTime.toDuration())
        } catch (broken: StepFailure) {
          if (failure == null) failure = ExportError.UnsupportedEffect(broken.specId, broken.reason)
          source
        } catch (broken: Exception) {
          if (failure == null) failure = ExportError.InvalidComposition(broken.message ?: broken.toString())
          source
        }
      request.finish(frame, context)
    }
  }

  private fun compose(
    state: ChainSnapshot,
    source: CIImage,
    time: Duration,
  ): CIImage {
    val resolved = state.resolved
    val size = state.output.size

    // A time outside every span is a hole in the timeline, which the last span covering the
    // composition's full duration already rules out. Passing the frame through keeps one from
    // failing the whole export.
    val span = state.spans.firstOrNull { it.covers(time) }
    var image = source

    if (span != null) {
      // A still is what its whole span draws, so it stands in for the segment AVFoundation decoded
      // before anything measures or grades the frame. Everything after this is the chain a video
      // clip already runs.
      span.still?.let { image = stills.of(it) }
      image = image.rotated(span.rotationDegrees)
      image = image.stepped(span.effects, FrameInfo(span.attributes, time))
    }

    image = image.stepped(resolved.compositionGeometry, FrameInfo(state.geometryAttributes, time))

    // A gap has no real clip frame to blur, only whatever placeholder AVFoundation handed back,
    // so a blurred fill falls back to black there the same way it always has.
    val fill = if (span == null && resolved.fill is Fill.Blurred) Fill.Black else resolved.fill
    val frame = FrameInfo(state.compositionAttributes, time)

    if (fill.derivesFromFrame) {
      // Picture, not furniture: today's order is the contract, so the fill is composited in first
      // and the composition's own effects grade it along with the rest of the frame.
      image = image.fittedTo(size, resolved.fit, fill, state.encodedTransfer)
      return image.stepped(resolved.compositionEffects, frame)
    }

    // A named colour is furniture. It is laid in without the fill composited yet, graded, and only
    // blended against the fill afterwards, so a composition effect never reaches a colour it was
    // not given to grade.
    val laid = image.laidInto(size, resolved.fit)
    val graded = laid.stepped(resolved.compositionEffects, frame)
    return graded.over(image.fillImage(fill, size, state.encodedTransfer), mask = laid, size = size)
  }

  /**
   * Runs a resolved chain, naming the effect that broke if one does.
   *
   * A resolver is third-party code and this is the only place it runs, so which spec it was is
   * knowable here and nowhere later.
   */
  private fun CIImage.stepped(
    effects: List<ResolvedEffect>,
    frame: FrameInfo,
  ): CIImage {
    var image = this
    effects.forEach { resolved ->
      image =
        try {
          resolved.effect.step.apply(image, frame)
        } catch (broken: Exception) {
          throw StepFailure(resolved.specId, broken.message ?: broken.toString())
        }
    }
    return image
  }

  /**
   * Bakes the container's rotation into the pixels.
   *
   * Apple records orientation in the track's preferred transform, and the flag that applies it is
   * ignored the moment a video composition is set. `displaySize` is already the rotated frame, so
   * every effect after this measures against the frame it was resolved for.
   */
  private fun CIImage.rotated(degrees: Int): CIImage {
    if (degrees % FULL_TURN == 0) return this
    return imageByApplyingTransform(CGAffineTransformMakeRotation(degrees * PI / STRAIGHT_ANGLE)).atOrigin()
  }

  /**
   * Lays the frame into the output rect and composites it over the fill.
   *
   * The composite is not only for [Fit.Contain]'s bars. `finishWithImage` wants an image covering
   * the whole render rect, and a frame that lands a pixel short of one edge after rounding leaves
   * AVFoundation to fill the difference with whatever the buffer already held.
   */
  private fun CIImage.fittedTo(
    size: Size,
    fit: Fit,
    fill: Fill,
    transfer: HdrTransfer?,
  ): CIImage {
    val rect = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
    val background = fillImage(fill, size, transfer).imageByCroppingToRect(rect)
    return laidInto(size, fit).imageByCompositingOverImage(background)
  }

  /**
   * Lays this frame into the output rect at [fit], with nothing composited behind it yet.
   *
   * Split out from [fittedTo] so a caller that has to grade the frame before the fill is drawn in
   * can run this step on its own, and paint the fill in only afterwards.
   */
  private fun CIImage.laidInto(
    size: Size,
    fit: Fit,
  ): CIImage {
    val width = size.width.toDouble()
    val height = size.height.toDouble()
    val rect = CGRectMake(0.0, 0.0, width, height)

    val laid =
      extent
        .useContents {
          if (this.size.width <= 0.0 || this.size.height <= 0.0) return@useContents null

          val scaleX = width / this.size.width
          val scaleY = height / this.size.height
          val (sx, sy) =
            when (fit) {
              Fit.Contain -> min(scaleX, scaleY).let { it to it }
              Fit.Crop -> max(scaleX, scaleY).let { it to it }
              Fit.Stretch -> scaleX to scaleY
            }

          val dx = (width - this.size.width * sx) / 2.0
          val dy = (height - this.size.height * sy) / 2.0
          CGAffineTransformConcat(
            CGAffineTransformConcat(
              CGAffineTransformMakeTranslation(-origin.x, -origin.y),
              CGAffineTransformMakeScale(sx, sy),
            ),
            CGAffineTransformMakeTranslation(dx, dy),
          )
        }?.let(::imageByApplyingTransform) ?: this

    return laid.imageByCroppingToRect(rect)
  }

  /**
   * Blends this graded frame over [background], letting [mask]'s alpha decide where.
   *
   * `finishWithImage` wants an image covering the whole render rect, so the fill still has to be
   * composited in even when nothing shows it, just after the composition effects run instead of
   * before them. A plain source-over would add a graded pixel's garbage colour to the background at
   * alpha zero. `CIBlendWithAlphaMask` lerps on the mask's alpha instead, discarding it the way
   * every other backend's flatten does.
   *
   * A solid fill is an image of infinite extent, so it is cropped to [size] before the blend reads
   * it and the result covers the render rect rather than everything beyond it too.
   */
  private fun CIImage.over(
    background: CIImage,
    mask: CIImage,
    size: Size,
  ): CIImage {
    val rect = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
    return imageByApplyingFilter(
      "CIBlendWithAlphaMask",
      mapOf(
        "inputBackgroundImage" to background.imageByCroppingToRect(rect),
        "inputMaskImage" to mask,
      ),
    )
  }

  /**
   * Lowers a [Fill] to the image it paints, drawn from this frame where the fill needs one.
   *
   * A fill this module does not recognise falls back to black rather than failing, since [Fill]
   * gains new arms over time and an older backend has to keep working against one it predates.
   */
  @Suppress("REDUNDANT_ELSE_IN_WHEN")
  private fun CIImage.fillImage(
    fill: Fill,
    size: Size,
    transfer: HdrTransfer?,
  ): CIImage =
    when (fill) {
      is Fill.Solid -> CIImage(color = fillCIColor(fill.color, transfer))
      is Fill.Blurred -> blurredCover(size, fill)
      else -> CIImage(color = CIColor.blackColor)
    }

  /**
   * Scales this frame to cover [size], blurs it and crops away the blur's own soft edge.
   *
   * The blur runs before the scale up to cover, over this frame's own resolution rather than the
   * output's, so a 4K source costs the same blur as a 720p one. [Fill.Blurred.radius] is a
   * fraction of the output frame's shorter side, so it is converted to an output pixel radius
   * first and then divided back through the cover scale, landing at the size it was asked for
   * once the scale runs.
   */
  private fun CIImage.blurredCover(
    size: Size,
    blur: Fill.Blurred,
  ): CIImage {
    val width = size.width.toDouble()
    val height = size.height.toDouble()
    val source = extent
    val (sourceWidth, sourceHeight) = source.useContents { this.size.width to this.size.height }
    if (sourceWidth <= 0.0 || sourceHeight <= 0.0) return CIImage(color = CIColor.blackColor)

    val outputRadius = blur.sigmaFor(size).toDouble()
    val sourceSize = Size(sourceWidth.roundToInt(), sourceHeight.roundToInt())
    val scale = coverScale(sourceSize, size).toDouble()

    // Clamping before the blur and cropping back to the original extent afterwards keeps the
    // Gaussian from pulling in transparent black at the edges, which would otherwise darken them.
    val blurred =
      imageByClampingToExtent()
        .imageByApplyingGaussianBlurWithSigma(outputRadius / scale)
        .imageByCroppingToRect(source)

    val covered =
      blurred
        .imageByApplyingFilter("CILanczosScaleTransform", mapOf(kCIInputScaleKey to scale))
        .trimmedToCover(width, height, margin = 2 * outputRadius)

    return if (blur.dim <= 0f) covered else covered.dimmed(blur.dim)
  }

  /**
   * Crops a [margin] off every side, then scales what remains up to exactly cover [width] by
   * [height].
   *
   * This receiver already covers that frame, so the crop trims off the blur's own soft boundary
   * before the scale spreads the sharp remainder back across the whole output.
   */
  private fun CIImage.trimmedToCover(
    width: Double,
    height: Double,
    margin: Double,
  ): CIImage {
    val trimmed = CGRectInset(extent, margin, margin)
    val fitted = AVMakeRectWithAspectRatioInsideRect(CGSizeMake(width, height), trimmed)
    return fitted.useContents {
      val scale = width / this.size.width
      val transform =
        CGAffineTransformConcat(
          CGAffineTransformConcat(
            CGAffineTransformMakeTranslation(
              -(origin.x + this.size.width / 2.0),
              -(origin.y + this.size.height / 2.0),
            ),
            CGAffineTransformMakeScale(scale, scale),
          ),
          CGAffineTransformMakeTranslation(width / 2.0, height / 2.0),
        )
      this@trimmedToCover.imageByApplyingTransform(transform)
    }
  }

  /**
   * Darkens this image's colour channels by [dim], leaving its alpha untouched.
   *
   * `CIColorMatrix` runs on linear light rather than on the encoded value a dim is defined against,
   * so the shared gain for a linear pipeline is what goes in here. Multiplying by the encoded gain
   * directly would land lighter than it was asked to.
   */
  private fun CIImage.dimmed(dim: Float): CIImage {
    val scale = linearDimGain(dim).toDouble()
    return imageByApplyingFilter(
      "CIColorMatrix",
      mapOf(
        "inputRVector" to CIVector.vectorWithX(scale, 0.0, 0.0, 0.0),
        "inputGVector" to CIVector.vectorWithX(0.0, scale, 0.0, 0.0),
        "inputBVector" to CIVector.vectorWithX(0.0, 0.0, scale, 0.0),
      ),
    )
  }

  // Core Image transforms about the coordinate origin, so a rotation pushes the extent into
  // negative space and it has to be brought back.
  private fun CIImage.atOrigin(): CIImage =
    extent.useContents {
      this@atOrigin.imageByApplyingTransform(CGAffineTransformMakeTranslation(-origin.x, -origin.y))
    }

  private companion object {
    const val STRAIGHT_ANGLE = 180.0
    const val FULL_TURN = 360

    /**
     * A context to render through, or null when this process cannot build one.
     *
     * Kotlin/Native raises at the constructor, not at the first use of what it handed back, so an
     * init that answers nil is caught here and nowhere later.
     */
    fun renderingContext(): CIContext? =
      try {
        CIContext()
      } catch (absent: NullPointerException) {
        null
      }
  }
}

/**
 * Everything one frame of a [CoreImageChain] is drawn from, taken together.
 *
 * A parameter edit produces a new composition and a new set of spans, and the two only describe the
 * same frame as each other. Held as one immutable object so a render reads a consistent pair rather
 * than one edit's spans against another edit's composition.
 *
 * @property resolved The composition the frame is drawn from.
 * @property spans Where each clip sits on the timeline, and what it draws.
 * @property encodesHdr Whether an HDR grade reaches the encoder.
 */
internal class ChainSnapshot(
  val resolved: ResolvedComposition,
  val spans: List<ClipSpan>,
  val encodesHdr: Boolean,
) {
  val output: OutputFormat = resolved.output

  val transfer: HdrTransfer? = resolved.hdrTransfer

  /**
   * The transfer function reaching the encoder, or null when the export writes SDR.
   *
   * A tone-mapped export is SDR by the time a fill is drawn, however the sources were graded.
   */
  val encodedTransfer: HdrTransfer? = transfer.takeIf { encodesHdr }

  /**
   * What composition-level geometry measures against.
   *
   * The frame after every clip and track effect, since geometry is what pins it to [output]'s size
   * rather than something that already runs inside it.
   */
  val geometryAttributes: Attributes =
    Attributes(
      inputSize = resolved.compositionInputSize,
      outputSize = output.size,
      layoutSize = resolved.compositionInputSize,
      colorSpace = if (encodesHdr) ColorSpace.Bt2020 else ColorSpace.Bt709,
      hdrTransfer = encodedTransfer,
      frameRate = output.frameRate?.toFloat(),
    )

  /**
   * What a composition-level effect that runs after geometry measures against. The output frame on
   * either side, since by the time one runs the frame has been pinned to it.
   */
  val compositionAttributes: Attributes =
    Attributes(
      inputSize = output.size,
      outputSize = output.size,
      layoutSize = resolved.layoutSize,
      colorSpace = if (encodesHdr) ColorSpace.Bt2020 else ColorSpace.Bt709,
      hdrTransfer = encodedTransfer,
      frameRate = output.frameRate?.toFloat(),
    )
}

/**
 * One resolver's step, and what it threw.
 *
 * Carried only from the step to the handler that called it, so the effect can be named in the
 * failure a caller reads.
 */
private class StepFailure(
  val specId: String,
  val reason: String,
) : Exception(reason)
