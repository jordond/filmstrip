package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.derivesFromFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.drawsSameFormatAs
import dev.jordond.filmstrip.transform.internal.resolve.effectsOverFill
import dev.jordond.filmstrip.transform.internal.resolve.effectsUnderFill
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import platform.AVFoundation.AVAsynchronousCIImageFilteringRequest
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/**
 * What every frame goes through, from the buffer AVFoundation decoded to the one it encodes.
 *
 * The handler is given a composition time and nothing else, so the clip a frame came from is found
 * by range lookup over [ClipSpan]. A span drawing a still puts that photo in first, in place of the
 * segment its slot was cut from. The clip's container rotation is baked in next, then its own
 * effects run, composition-level geometry included, then the frame is placed in [OutputFormat.size] at the clip's own
 * placement.
 * Composition-level effects run next, over the picture that frame produced. A named fill color is
 * painted into whatever the picture does not cover once every effect but the overlays has run, so a
 * grade never reaches a bar or a gap it was not given a color for, and the overlays then draw over
 * the whole frame, bars and gaps included. That is the same order every backend runs, which is what
 * makes a normalised measurement in a composition-level effect a fraction of the same frame on each.
 *
 * There is no overlay batching here and no analogue of Android's sampler budget. Core Image fuses a
 * chain of composites into one pass over the frame, whereas media3 spends a whole render pass per
 * `OverlayEffect`, which is the only reason that coalescing exists.
 *
 * A preview shares one `AVVideoComposition` with the export it previews, and reassigning that on a
 * playing `AVPlayerItem` stalls it. Everything a parameters-only edit can change therefore lives
 * behind one reference that [render] re-reads on every frame and [updateParameters] replaces.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal class CoreImageChain(
  resolved: ResolvedComposition,
  spans: List<ClipSpan>,
  encodesHdr: Boolean,
) {
  /**
   * Everything one frame is drawn from and what a step drawing it threw, replaced together.
   *
   * Written from whichever queue an edit arrives on and from AVFoundation's render queue, which is
   * never that one. A swap stores a fresh pair with no failure, and a failure is only set by a
   * compare-and-set against the pair holding the snapshot the frame was drawn from.
   */
  private val latch = AtomicReference(Latched(ChainSnapshot(resolved, spans, encodesHdr), failure = null))

  private val snapshot: ChainSnapshot get() = latch.load().snapshot

  public val output: OutputFormat get() = snapshot.output

  public val transfer: HdrTransfer? get() = snapshot.transfer

  /**
   * Whether an HDR grade reaches the encoder, which is not always what was asked for.
   */
  public val encodesHdr: Boolean get() = snapshot.encodesHdr

  /**
   * Where each clip sits on the timeline, and what it draws, as of the last swap.
   */
  internal val spans: List<ClipSpan> get() = snapshot.spans

  /**
   * Built once and reused for every frame, or null when this process cannot build one at all.
   *
   * A context carries the compiled kernels and their caches, so owning one beats letting
   * AVFoundation make its own. It answers nil where there is no rendering device, which a headless
   * test process is, and AVFoundation renders through one of its own when handed none.
   */
  private val context: CIContext? = renderingContext()

  /**
   * The context a compositor renders this chain's frames through, or null when this process cannot build one.
   */
  internal val renderer: CIContext? get() = context

  /**
   * The photos this chain draws, opened once each.
   *
   * Kept outside the snapshot, so a parameters-only edit rebuilding the spans keeps the images it
   * already opened instead of reading them again on the next frame.
   */
  private val stills = CoreImageStills()

  /**
   * What a resolver's step threw, or null while nothing has.
   *
   * Written on AVFoundation's render queue and read once the run has stopped.
   */
  public val failure: FilmstripError? get() = latch.load().failure

  /**
   * Called with [failure] on AVFoundation's render queue the moment a step first throws, so a preview can report it
   * while frames are still being drawn.
   *
   * It is called again only after [updateParameters] clears [failure] and a step throws once more. A frame still in
   * flight from the parameters a swap replaced neither sets [failure] nor calls this.
   */
  @Volatile
  public var onFailure: ((FilmstripError) -> Unit)? = null

  /**
   * Swaps in the effect parameters [resolved] carries, for every frame drawn from here on, and clears [failure].
   *
   * Each span keeps the slot it already holds and picks up its clip's new effects, so nothing is
   * laid onto an `AVMutableComposition` again and the `AVVideoComposition` a player is showing
   * stays the object it was handed. A frame already in flight finishes against the parameters it
   * started on.
   *
   * The plan's bitrate, codecs and audio format can move, since no frame is drawn from them. Whether audio is kept
   * cannot, since nothing a swap touches lays or removes an audio track.
   *
   * @param resolved The same timeline, planned again with different effect parameters.
   * @throws IllegalArgumentException When [takesParametersOf] says no, which needs the whole graph built again from
   *   scratch instead.
   */
  public fun updateParameters(resolved: ResolvedComposition) {
    val current = snapshot
    val refusal = swapRefusalOf(resolved, current)
    require(refusal == null) { refusal.orEmpty() }

    latch.store(Latched(ChainSnapshot(resolved, current.spans.respannedOnto(resolved), current.encodesHdr), null))
  }

  /**
   * Whether [updateParameters] can take [resolved], which holds while it draws the format this chain was built for,
   * over the clip durations its spans were laid out against and the frames the video track already holds.
   */
  public fun takesParametersOf(resolved: ResolvedComposition): Boolean = swapRefusalOf(resolved, snapshot) == null

  private fun swapRefusalOf(
    resolved: ResolvedComposition,
    current: ChainSnapshot,
  ): String? =
    when {
      !current.resolved.drawsSameFormatAs(resolved) -> {
        "A swap cannot change the format it draws. It was ${current.resolved.drawnFormat}, and " +
          "${resolved.drawnFormat} was given."
      }
      resolved.videoClipDurations != current.resolved.videoClipDurations -> {
        "A swap keeps the spans it already has, so it cannot change the timeline they cover. It was " +
          "${current.resolved.videoClipDurations}, and ${resolved.videoClipDurations} was given."
      }
      !current.resolved.laysSameFramesAs(resolved) -> {
        "A swap keeps the video track it already has, and a copy of this clip lays a frame a transcode leaves out."
      }
      else -> {
        null
      }
    }

  private val ResolvedComposition.drawnFormat: String
    get() = "${output.size} at ${output.frameRate} fps, $hdr, transfer $hdrTransfer, audio ${output.audioCodec}"

  /**
   * Renders one frame and hands it back to AVFoundation.
   *
   * Every frame is wrapped in its own autorelease pool. Core Image allocates heavily per frame and
   * the handler runs on AVFoundation's queue, which drains no pool between requests, so without one
   * a long export climbs until it is killed.
   *
   * A step that throws is caught here. This is a callback from Objective-C, and an exception
   * crossing that boundary terminates the process instead of reaching a caller. The frame is passed
   * through and the first reason is kept for the run to report and handed to [onFailure], as [drawn] says.
   */
  @OptIn(BetaInteropApi::class)
  public fun render(request: AVAsynchronousCIImageFilteringRequest) {
    autoreleasepool {
      // Read once. A swap landing between two reads would draw one edit's spans against another
      // edit's composition, which is a frame neither of them describes.
      val state = snapshot
      request.finish(drawn(state, request.sourceFrame(), request.compositionTime.toDuration()), context)
    }
  }

  /**
   * What everything [render] draws is drawn from right now, replaced whole by [updateParameters].
   */
  internal val current: ChainSnapshot get() = snapshot

  /**
   * [source] drawn at [time] against [state], or [source] itself when a step throws.
   *
   * The first step to throw sets [failure] and calls [onFailure], unless [state] is no longer [current]. A frame drawn
   * against parameters a swap has since replaced is stale, so what it throws is dropped.
   */
  internal fun drawn(
    state: ChainSnapshot,
    source: CIImage,
    time: Duration,
  ): CIImage =
    try {
      compose(state, source, time)
    } catch (broken: StepFailure) {
      failed(state, FilmstripError.UnsupportedEffect(broken.specId, broken.reason))
      source
    } catch (broken: Exception) {
      failed(state, FilmstripError.InvalidComposition(broken.message ?: broken.toString()))
      source
    }

  private fun failed(
    state: ChainSnapshot,
    error: FilmstripError,
  ) {
    while (true) {
      val held = latch.load()
      if (held.snapshot !== state || held.failure != null) return
      if (latch.compareAndSet(held, Latched(state, error))) break
    }
    onFailure?.invoke(error)
  }

  /**
   * The snapshot frames are drawn from and the failure latched against it, compared by identity.
   *
   * A failure belongs to the snapshot it sits beside, so a swap replacing the snapshot clears it
   * in the same store.
   */
  private class Latched(
    val snapshot: ChainSnapshot,
    val failure: FilmstripError?,
  )

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

    // Inside the held tail every frame is drawn at the primary's last covered slot, so no effect at
    // any level moves on while the picture holds.
    val drawnAt = span?.heldFrom?.let { minOf(time, it) } ?: time

    if (span != null) {
      // A still is what its whole span draws, so it stands in for the segment AVFoundation decoded
      // before anything measures or grades the frame. Everything after this is the chain a video
      // clip already runs.
      span.still?.let { image = stills.of(it) }
      image = image.rotated(span.rotationDegrees)
      image = image.throughChain(span, FrameInfo(span.attributes, drawnAt), resolved.fill)
    } else {
      // A gap has no picture. The placeholder AVFoundation hands back is opaque black, and laid in as
      // the picture it would cover the fill across the whole frame, so it goes in as clear instead.
      image = image.cleared()
    }

    // A gap has no real clip frame to blur, only whatever placeholder AVFoundation handed back,
    // so a blurred fill falls back to black there the same way it always has.
    val fill = if (span == null && resolved.fill is Fill.Blurred) Fill.Black else resolved.fill
    val rect = span?.drawnRect ?: NormalizedRect.Full
    val frame = FrameInfo(state.compositionAttributes, drawnAt)

    if (fill.derivesFromFrame) {
      // Picture, not furniture: the fill is composited in first and the composition's own effects
      // grade it along with the rest of the frame.
      image = image.fittedTo(size, rect, fill, state.encodedTransfer)
      return image.stepped(resolved.effectsUnderFill, frame).stepped(resolved.effectsOverFill, frame)
    }

    // A named color is furniture. The picture is laid in without the fill and graded, then blended
    // against the fill so no grade reaches it, and only then do the overlays draw, over bars and
    // gaps as well as the picture.
    val laid = image.laidAt(size, rect)
    val graded = laid.stepped(resolved.effectsUnderFill, frame)
    val flattened = graded.over(image.fillImage(fill, size, state.encodedTransfer), mask = laid, size = size)
    return flattened.stepped(resolved.effectsOverFill, frame)
  }

  /**
   * Runs [span]'s effects, building each of the clip's canvases right after the crop that makes it.
   *
   * The canvas bars are clear under a named fill color, so the fill shows through them when it is painted in. A fill
   * drawn from the frame is composited in before the effects run, so the bars are black under it, the way they are on
   * every backend.
   *
   * Each effect is handed the frame it really runs on, so one after a rotate or a canvas measures the rotated frame or
   * the canvas rather than the clip's source.
   */
  private fun CIImage.throughChain(
    span: ClipSpan,
    frame: FrameInfo,
    fill: Fill,
  ): CIImage {
    val bars = if (fill.derivesFromFrame) CIColor.blackColor else CIColor.clearColor
    var image = this
    span.effects.forEachIndexed { index, resolved ->
      image = image.stepped(listOf(resolved), frame.measuring(image, span.attributes))
      val canvas = span.canvasEffectIndices.indexOf(index)
      if (canvas >= 0) image = image.inCanvas(span.placement.canvases[canvas], bars)
    }
    return image
  }

  /**
   * Runs a resolved chain, naming the effect that broke if one does.
   *
   * A resolver is third-party code and this is the only place it runs, so which spec it was is
   * knowable here and nowhere later.
   *
   * A [EffectStage.Composite] step is cut back to the frame it was handed. An overlay an offset
   * carries past the edge would otherwise grow the image, and a clip laid into the output by its
   * extent would move over to make room for it.
   */
  private fun CIImage.stepped(
    effects: List<ResolvedEffect>,
    frame: FrameInfo,
  ): CIImage {
    var image = this
    effects.forEach { resolved ->
      val input = image
      image =
        try {
          resolved.effect.step.apply(input, frame)
        } catch (broken: Exception) {
          throw StepFailure(resolved.specId, broken.message ?: broken.toString())
        }
      if (resolved.stage == EffectStage.Composite) image = image.imageByCroppingToRect(input.extent)
    }
    return image
  }

  private companion object {
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
 * One resolver's step, and what it threw.
 *
 * Carried only from the step to the handler that called it, so the effect can be named in the
 * failure a caller reads.
 */
private class StepFailure(
  val specId: String,
  val reason: String,
) : Exception(reason)
