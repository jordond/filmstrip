package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AssetCache
import dev.jordond.filmstrip.avfoundation.internal.chain.ClipSpan
import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.chain.laysVideoOnGrid
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
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
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
import platform.AVFoundation.videoCompositionWithAsset
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid

/**
 * The AVFoundation graph a plan runs as.
 *
 * @property videoComposition Null when the output carries no video track, which is what
 *   [AudioSpec.AudioOnly] resolves to.
 * @property audioMix Null when nothing scales a gain, so the reader passes samples through.
 * @property encodesHdr Whether an HDR grade reaches the encoder, which is not always what was
 *   asked for.
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
) {
  /**
   * The size every frame is drawn at, or null when the output carries no video track.
   */
  public val renderSize: Size? get() = chain?.output?.size

  /**
   * Whether [updateParameters] reaches any frame, which takes a video track to draw through.
   */
  public val swapsParameters: Boolean get() = chain != null

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
   * @throws IllegalArgumentException When [resolved] moves something the graph was laid out against, which needs
   *   the whole graph built again through [toAvComposition] instead.
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
 * @throws AvLoweringFailure when a source or a track cannot be opened.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun ResolvedComposition.toAvComposition(): AvComposition {
  val keepAudio = output.audioCodec != AudioCodec.None
  val keepVideo = audio != AudioSpec.AudioOnly
  val composition = AVMutableComposition()
  val assets = AssetCache()
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

  val encodesHdr = hdrTransfer != null
  val spans = placements.toSpans(composition.duration.toDuration(), output.size, layoutSize, hdrTransfer)
  val chain = if (keepVideo) CoreImageChain(this, spans, encodesHdr) else null

  return AvComposition(
    composition = composition,
    videoComposition = chain?.let { composition.videoComposition(it, encodesHdr, holdsTail = !tail.isEmpty()) },
    audioMix = mixed.toAudioMix(),
    encodesHdr = encodesHdr,
    transfer = hdrTransfer,
    laidSpans = spans,
    chain = chain,
  )
}

/**
 * The video composition, with the filter handler already attached.
 *
 * Built through `videoCompositionWithAsset:applyingCIFiltersWithHandler:`, whose block bridges to a
 * Kotlin lambda. A custom `AVVideoCompositing` is an Objective-C class handed to AVFoundation,
 * which Kotlin/Native cannot register.
 *
 * That composition renders a frame for each sample the video track holds, so a tick stretched over
 * a held tail would be rendered once however many slots it covers. When [holdsTail] is set it
 * renders one frame every frame duration instead, up to the end of the composition. The audio under
 * the tail can run up to half a slot past the last one, and the export stops the video on the last
 * slot so that time gets no frame of its own.
 *
 * @param holdsTail Whether the primary's last frame is held under a longer audio track.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableComposition.videoComposition(
  chain: CoreImageChain,
  encodesHdr: Boolean,
  holdsTail: Boolean,
): AVMutableVideoComposition {
  val output = chain.output
  val composition =
    AVMutableVideoComposition.videoCompositionWithAsset(this) { request ->
      request?.let(chain::render)
    }

  composition.setRenderSize(CGSizeMake(output.size.width.toDouble(), output.size.height.toDouble()))
  composition.setFrameDuration(CMTimeMake(value = 1, timescale = output.frameRate))
  if (holdsTail) composition.setSourceTrackIDForFrameTiming(kCMPersistentTrackID_Invalid)

  if (encodesHdr) {
    composition.setColorPrimaries(AVVideoColorPrimaries_ITU_R_2020)
    composition.setColorTransferFunction(
      if (chain.transfer == HdrTransfer.Pq) {
        AVVideoTransferFunction_SMPTE_ST_2084_PQ
      } else {
        AVVideoTransferFunction_ITU_R_2100_HLG
      },
    )
    composition.setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_2020)
    // The source's mastering and content-light metadata is carried through, since nothing in the
    // chain changes what the grade was mastered for.
    composition.setPerFrameHDRDisplayMetadataPolicy(AVVideoCompositionPerFrameHDRDisplayMetadataPolicyPropagate)
  } else {
    // Naming the target is what tone-maps. Core Image renders into it, so a BT.2020 source lands
    // inside Rec.709 without a mode that says so.
    composition.setColorPrimaries(AVVideoColorPrimaries_ITU_R_709_2)
    composition.setColorTransferFunction(AVVideoTransferFunction_ITU_R_709_2)
    composition.setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_709_2)
  }

  return composition
}

private const val NO_VIDEO_TRACK = "AVFoundation refused a video track on the composition."

private const val NOTHING_INSERTED =
  "No clip in this composition contributed a track AVFoundation could read, so the export would " +
    "write a file with nothing in it."
