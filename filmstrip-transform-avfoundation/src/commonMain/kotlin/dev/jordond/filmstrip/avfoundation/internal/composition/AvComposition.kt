package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AssetCache
import dev.jordond.filmstrip.avfoundation.internal.StretchedAudio
import dev.jordond.filmstrip.avfoundation.internal.chain.ClipSpan
import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.chain.laysVideoOnGrid
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesKernelLimit
import dev.jordond.filmstrip.avfoundation.internal.chain.videoTrack
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.layOnto
import dev.jordond.filmstrip.avfoundation.internal.tailHeldBy
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.internal.toAudioMix
import dev.jordond.filmstrip.avfoundation.internal.toSpans
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AppleScratch
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_2020
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoCompositionPerFrameHDRDisplayMetadataPolicyPropagate
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_2100_HLG
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_2020
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.setColorPrimaries
import platform.AVFoundation.setColorTransferFunction
import platform.AVFoundation.setColorYCbCrMatrix
import platform.AVFoundation.setPerFrameHDRDisplayMetadataPolicy
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.videoCompositionWithAsset
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreMedia.kCMTimeZero

/**
 * The AVFoundation graph a plan runs as.
 *
 * @property videoComposition Null when the output carries no video track, which is what
 *   [AudioSpec.AudioOnly] resolves to.
 * @property audioMix Null when nothing scales a gain, so the reader passes samples through.
 * @property encodesHdr Whether an HDR grade reaches the encoder, which is not always what was
 *   asked for.
 * @property drawnByCompositor Whether [AvCompositor] draws the frames, on the clock rather than per sample.
 * @property timedByGrid Whether the filter handler draws on the frame grid rather than per sample of the source track.
 */
@InternalFilmstripApi
public class AvComposition internal constructor(
  public val composition: AVMutableComposition,
  public val videoComposition: AVMutableVideoComposition?,
  public val audioMix: AVMutableAudioMix?,
  public val encodesHdr: Boolean,
  public val transfer: HdrTransfer?,
  private val laidSpans: List<ClipSpan>,
  internal val chain: CoreImageChain?,
  internal val drawnByCompositor: Boolean,
  internal val timedByGrid: Boolean,
) {
  /**
   * The size every frame is drawn at, or null when the output carries no video track.
   */
  public val renderSize: Size? get() = chain?.output?.size

  /**
   * Whether [updateParameters] can take [resolved] on the graph as it stands. False when there is no video track or
   * [resolved] moves something the graph was laid out against, such as whether audio was laid, which is the case for
   * [toAvComposition].
   */
  public fun takesParametersOf(resolved: ResolvedComposition): Boolean = chain?.takesParametersOf(resolved) == true

  /**
   * Called on AVFoundation's render queue the moment a step first throws while drawing a frame, so a preview can
   * report it while frames are still being drawn.
   *
   * It is called again only after [updateParameters] clears the failure and a step throws once more. Setting it does
   * nothing when there is no video track.
   */
  public var onChainFailure: ((FilmstripError) -> Unit)?
    get() = chain?.onFailure
    set(value) {
      chain?.onFailure = value
    }

  /**
   * Swaps in the effect parameters [resolved] carries for every frame drawn from here on, and clears what
   * [chainFailure] held.
   *
   * The `AVVideoComposition` a player is showing stays the object it was handed, and nothing is laid onto
   * [composition] again. Does nothing when there is no video track.
   *
   * @param resolved The same timeline, planned again with different effect parameters.
   * @throws IllegalArgumentException When [takesParametersOf] says no, which needs the whole graph built again through
   *   [toAvComposition] instead.
   */
  public fun updateParameters(resolved: ResolvedComposition) {
    chain?.updateParameters(resolved)
  }

  /**
   * Where each clip sits on the timeline, and what it draws.
   *
   * Read off the chain wherever there is one, so a parameter swap shows here rather than leaving
   * this describing an edit that stopped rendering.
   */
  internal val spans: List<ClipSpan> get() = chain?.spans ?: laidSpans

  /**
   * What the filter handler could not draw, or null when every frame went through.
   *
   * The handler cannot fail an export on its own. It runs inside AVFoundation, where an exception
   * terminates the process, so it records the failure and passes the frame on. The run asks once
   * the pumps have drained.
   */
  public fun chainFailure(): FilmstripError? = chain?.failure
}

/**
 * Builds the AVFoundation composition this plan runs as.
 *
 * The size stage is not a step in any clip's chain. Every clip is fitted to [OutputFormat.size]
 * inside the filter handler at its own placement, after that clip's own effects and before the composition-level ones,
 * which is what lets clips of differing sizes concatenate. See [CoreImageChain].
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @throws AvLoweringFailure when a source or a track cannot be opened.
 * @throws MediaBytesFileFailure when an in-memory source could not be written to a file.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toAvComposition(bytesRoot: String = AppleScratch.defaultRoot): AvComposition =
  toAvComposition(sdKernelLimit = { sdPrimariesKernelLimit() }, bytesRoot = bytesRoot)

/**
 * Builds the AVFoundation composition this plan runs as, with [sdKernelLimit] saying why this device cannot draw an SD
 * clip's primaries.
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @param stretched The audio an export rendered to its clips' speeds, laid at speed 1 in place of those clips' sources.
 * Null for a preview, which scales its audio tracks and leaves the pitch to its player.
 * @throws AvLoweringFailure when a source or a track cannot be opened, or when the plan draws an SD clip's primaries
 * and [sdKernelLimit] names a limit.
 * @throws MediaBytesFileFailure when an in-memory source could not be written to a file.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ResolvedComposition.toAvComposition(
  sdKernelLimit: () -> String?,
  bytesRoot: String = AppleScratch.defaultRoot,
  stretched: StretchedAudio? = null,
): AvComposition {
  val keepAudio = output.audioCodec != AudioCodec.None
  val keepVideo = audio != AudioSpec.AudioOnly
  val encodesHdr = hdrTransfer != null
  val composition = AVMutableComposition()
  val assets =
    AssetCache(bytesRoot = bytesRoot, drawsSd = !encodesHdr, stretched = stretched, kernelLimit = sdKernelLimit)
  val tail = videoTrack?.let { tailHeldBy(it) } ?: LongRange.EMPTY

  val placements =
    videoTrack
      ?.let { track ->
        val media =
          composition.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
            ?: throw AvLoweringFailure(NO_VIDEO_TRACK)
        // The preferred transform is a player instruction, and a composition carrying a video
        // composition ignores it. Every clip bakes its own rotation instead, so this says the frames
        // are already the way round they should be shown.
        media.preferredTransform = CGAffineTransformIdentity.readValue()
        track.layOnto(media, AVMediaTypeVideo, assets, output.frameRate, laysVideoOnGrid, tail)
      }.orEmpty()

  val mixed =
    tracks
      .filter { keepAudio && it.content != TrackContent.Video }
      .mapNotNull { track ->
        val media =
          composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid)
            ?: return@mapNotNull null
        media to track.layOnto(media, AVMediaTypeAudio, assets, output.frameRate, onGrid = false)
      }

  if (placements.isEmpty() && mixed.all { it.second.isEmpty() }) {
    throw AvLoweringFailure(NOTHING_INSERTED)
  }

  val spans = placements.toSpans(composition.duration.toDuration(), output.size, layoutSize, hdrTransfer)
  val chain = if (keepVideo) CoreImageChain(this, spans, encodesHdr) else null
  val timedByGrid = !tail.isEmpty() || placements.any { it.clip.speed != 1.0 }
  // An audio-only clip can bring a grade into the plan that no laid video carries, which leaves nothing to tone map.
  val toneMaps = hdr == ResolvedHdr.ToneMap && spans.any { it.sourceTransfer != null }
  val holdsSd = spans.any { it.sdColor != null }
  if (holdsSd && !toneMaps) sdRouteTransferLimit(placements, bytesRoot)?.let { throw AvLoweringFailure(it) }
  val drawnByCompositor = chain != null && (toneMaps || holdsSd)
  val videoComposition =
    chain?.let {
      if (drawnByCompositor) {
        composition.compositorComposition(it)
      } else {
        composition.videoComposition(it, encodesHdr, timedByGrid)
      }
    }

  return AvComposition(
    composition = composition,
    videoComposition = videoComposition,
    audioMix = mixed.toAudioMix(),
    encodesHdr = encodesHdr,
    transfer = hdrTransfer,
    laidSpans = spans,
    chain = chain,
    drawnByCompositor = drawnByCompositor,
    timedByGrid = timedByGrid,
  )
}

/**
 * The video composition of a plan with neither a graded clip to tone map nor an SD clip to draw, with the filter
 * handler already attached.
 *
 * That composition renders a frame for each sample the video track holds, so a tick stretched over
 * a held tail would be rendered once however many slots it covers. When [timedByGrid] is set it
 * renders one frame every frame duration instead, up to the end of the composition. The audio under
 * the tail can run up to half a slot past the last one, and the export stops the video on the last
 * slot so that time gets no frame of its own.
 *
 * @param timedByGrid Whether the frame grid rather than the source track says when frames fall, which a held tail and a
 * clip at speed both need, since a scaled source track would write frames at its own scaled rate.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableComposition.videoComposition(
  chain: CoreImageChain,
  encodesHdr: Boolean,
  timedByGrid: Boolean,
): AVMutableVideoComposition {
  val output = chain.output
  val composition =
    AVMutableVideoComposition.videoCompositionWithAsset(this) { request ->
      request?.let(chain::render)
    }

  composition.setRenderSize(CGSizeMake(output.size.width.toDouble(), output.size.height.toDouble()))
  composition.setFrameDuration(CMTimeMake(value = 1, timescale = output.frameRate))
  if (timedByGrid) composition.setSourceTrackIDForFrameTiming(kCMPersistentTrackID_Invalid)

  if (encodesHdr) {
    composition.setColorPrimaries(AVVideoColorPrimaries_ITU_R_2020)
    composition.setColorTransferFunction((chain.transfer ?: HdrTransfer.Hlg).avTransferFunction())
    composition.setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_2020)
    // The source's mastering and content-light metadata is carried through, since nothing in the
    // chain changes what the grade was mastered for.
    composition.setPerFrameHDRDisplayMetadataPolicy(AVVideoCompositionPerFrameHDRDisplayMetadataPolicyPropagate)
  } else {
    composition.setColorPrimaries(AVVideoColorPrimaries_ITU_R_709_2)
    composition.setColorTransferFunction(AVVideoTransferFunction_ITU_R_709_2)
    composition.setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_709_2)
  }

  return composition
}

/**
 * The video composition of a tone mapped plan or one holding an SD clip, drawn by [AvCompositor].
 *
 * It names no colour of its own, since a clip's frame reaches the compositor as its own file carries it and each clip
 * can carry a different one. The one instruction covers the whole composition, gaps included, so a gap asks for a
 * frame with no source.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableComposition.compositorComposition(chain: CoreImageChain): AVMutableVideoComposition {
  val output = chain.output
  val track = tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
  val composition = AVMutableVideoComposition.videoComposition()

  composition.setCustomVideoCompositorClass(compositorClass())
  composition.setInstructions(
    listOf(AvCompositorInstruction(CMTimeRangeMake(kCMTimeZero.readValue(), duration), track.trackID, chain)),
  )
  composition.setRenderSize(CGSizeMake(output.size.width.toDouble(), output.size.height.toDouble()))
  composition.setFrameDuration(CMTimeMake(value = 1, timescale = output.frameRate))

  return composition
}

private fun HdrTransfer.avTransferFunction(): String? =
  when (this) {
    HdrTransfer.Pq -> AVVideoTransferFunction_SMPTE_ST_2084_PQ
    HdrTransfer.Hlg -> AVVideoTransferFunction_ITU_R_2100_HLG
  }

private const val NO_VIDEO_TRACK = "AVFoundation refused a video track on the composition."

private const val NOTHING_INSERTED =
  "No clip in this composition contributed a track AVFoundation could read, so the export would " +
    "write a file with nothing in it."
