package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AssetCache
import dev.jordond.filmstrip.avfoundation.internal.AudioStretchFailure
import dev.jordond.filmstrip.avfoundation.internal.AvAudioGraph
import dev.jordond.filmstrip.avfoundation.internal.LaidAudio
import dev.jordond.filmstrip.avfoundation.internal.StretchedAudio
import dev.jordond.filmstrip.avfoundation.internal.chain.ClipSpan
import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.chain.laysVideoOnGrid
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesKernelLimit
import dev.jordond.filmstrip.avfoundation.internal.chain.videoTrack
import dev.jordond.filmstrip.avfoundation.internal.copiedRuns
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.hasAudioEffects
import dev.jordond.filmstrip.avfoundation.internal.layOnto
import dev.jordond.filmstrip.avfoundation.internal.laySilence
import dev.jordond.filmstrip.avfoundation.internal.tailHeldBy
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.internal.toSpans
import dev.jordond.filmstrip.avfoundation.internal.writer.pcmReaderFormat
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
 * @property encodesHdr Whether an HDR grade reaches the encoder, which is not always what was
 *   asked for.
 * @property drawnByCompositor Whether [AvCompositor] draws the frames, on the clock rather than per sample.
 * @property timedByGrid Whether the filter handler draws on the frame grid rather than per sample of the source track.
 */
@InternalFilmstripApi
public class AvComposition internal constructor(
  public val composition: AVMutableComposition,
  public val videoComposition: AVMutableVideoComposition?,
  public val encodesHdr: Boolean,
  public val transfer: HdrTransfer?,
  private val laidSpans: List<ClipSpan>,
  internal val chain: CoreImageChain?,
  internal val drawnByCompositor: Boolean,
  internal val timedByGrid: Boolean,
  internal val audio: AvAudioGraph? = null,
  private val stretched: StretchedAudio? = null,
) {
  /**
   * The mix a reader or a player item plays the audio through. Null when nothing scales a gain and nothing runs an
   * audio effect, so samples pass through. [updateParameters] can replace it, and the caller then hands the new one
   * to its item.
   */
  public val audioMix: AVMutableAudioMix? get() = audio?.mix

  /**
   * The size every frame is drawn at, or null when the output carries no video track.
   */
  public val renderSize: Size? get() = chain?.output?.size

  /**
   * Whether [updateParameters] can take [resolved] on the graph as it stands. False when there is no video track or
   * [resolved] moves something the graph was laid out against, such as whether audio was laid, which is the case for
   * [toAvComposition].
   */
  public fun takesParametersOf(resolved: ResolvedComposition): Boolean =
    chain?.takesParametersOf(resolved) == true && audio?.takes(resolved) != false

  /**
   * Called on AVFoundation's render queue the moment a step first throws while drawing a frame, so a preview can
   * report it while frames are still being drawn. An audio effect that throws calls it from the audio thread.
   *
   * It is called again only after [updateParameters] clears the failure and a step throws once more. Setting it does
   * nothing when there is no video track.
   */
  public var onChainFailure: ((FilmstripError) -> Unit)?
    get() = chain?.onFailure
    set(value) {
      chain?.onFailure = value
      audio?.taps?.forEach { it.onFailure = value }
    }

  /**
   * Swaps in the effect parameters [resolved] carries for every frame drawn from here on, and its gains and audio
   * effects for the audio played from here on, and clears what [chainFailure] held.
   *
   * The `AVVideoComposition` a player is showing stays the object it was handed, and nothing is laid onto
   * [composition] again. With no video track only the audio changes.
   *
   * @param resolved The same timeline, planned again with different effect parameters.
   * @throws IllegalArgumentException When [takesParametersOf] says no, which needs the whole graph built again through
   *   [toAvComposition] instead.
   */
  public fun updateParameters(resolved: ResolvedComposition) {
    chain?.updateParameters(resolved)
    audio?.update(resolved)
  }

  /**
   * Removes the files this composition's audio was rendered to, once nothing plays it any more. Safe to call when
   * there are none, and more than once.
   */
  public fun discard() {
    stretched?.discard()
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
   * the pumps have drained. A clip's audio effect that threw in its tap is reported here the same way.
   */
  public fun chainFailure(): FilmstripError? = chain?.failure ?: audio?.failure
}

/**
 * Builds the AVFoundation composition this plan runs as.
 *
 * The size stage is not a step in any clip's chain. Every clip is fitted to [OutputFormat.size]
 * inside the filter handler at its own placement, after that clip's own effects and before the composition-level ones,
 * which is what lets clips of differing sizes concatenate. See [CoreImageChain].
 *
 * Reading a long clip's frame times takes a while, and stops when the caller is cancelled.
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @throws AvLoweringFailure when a source or a track cannot be opened, or a clip's frames cannot be laid.
 * @throws MediaBytesFileFailure when an in-memory source could not be written to a file.
 */
@InternalFilmstripApi
public suspend fun ResolvedComposition.toAvComposition(bytesRoot: String = AppleScratch.defaultRoot): AvComposition =
  toAvComposition(sdKernelLimit = { sdPrimariesKernelLimit() }, bytesRoot = bytesRoot)

/**
 * Builds the AVFoundation composition a player plays this plan through.
 *
 * A plan with an audio effect has the audio of every clip at a speed rendered to a file first, the way an export
 * does, since a player stretches a scaled track after the tap its effects run in. That takes a while for a long
 * clip, and the files last until [AvComposition.discard]. A plan with no audio effect scales its tracks and leaves
 * the pitch to the player.
 *
 * @param bytesRoot The directory an in-memory source and the rendered audio land under.
 * @throws AvLoweringFailure when a source or a track cannot be opened, a clip's frames cannot be laid, or a clip's
 * audio could not be rendered.
 * @throws MediaBytesFileFailure when an in-memory source could not be written to a file.
 */
@InternalFilmstripApi
public suspend fun ResolvedComposition.toPreviewComposition(
  bytesRoot: String = AppleScratch.defaultRoot,
): AvComposition {
  val stretched = if (hasAudioEffects) StretchedAudio(bytesRoot) else null
  try {
    stretched?.renderAll(this, bytesRoot)
    return toAvComposition(
      sdKernelLimit = { sdPrimariesKernelLimit() },
      bytesRoot = bytesRoot,
      stretched = stretched,
      plays = true,
    )
  } catch (failure: AudioStretchFailure) {
    stretched?.discard()
    throw AvLoweringFailure(failure.error.message)
  } catch (failure: Exception) {
    stretched?.discard()
    throw failure
  }
}

/**
 * Builds the AVFoundation composition this plan runs as, with [sdKernelLimit] saying why this device cannot draw an SD
 * clip's primaries. It stops when the caller is cancelled.
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @param stretched The audio an export rendered to its clips' speeds, laid at speed 1 in place of those clips' sources.
 * Null for a preview, which scales its audio tracks and leaves the pitch to its player.
 * @param writesCopy Whether an export reads a copy's packets through this composition, which picks how
 * [copiedRuns] lays its counted video.
 * @param plays Whether a player plays the composition, which has its taps apply each clip's gain and run the
 * composition's audio effects, and hands it [stretched] to discard.
 * @throws AvLoweringFailure when a source or a track cannot be opened, a clip's frames cannot be laid, or the plan
 * draws an SD clip's primaries and [sdKernelLimit] names a limit.
 * @throws MediaBytesFileFailure when an in-memory source could not be written to a file.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun ResolvedComposition.toAvComposition(
  sdKernelLimit: () -> String?,
  bytesRoot: String = AppleScratch.defaultRoot,
  stretched: StretchedAudio? = null,
  writesCopy: Boolean = false,
  plays: Boolean = false,
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
        track.layOnto(media, AVMediaTypeVideo, assets, output.frameRate, laysVideoOnGrid, tail, copiedRuns(writesCopy))
      }.orEmpty()

  val mixed =
    tracks.withIndex().mapNotNull { (index, track) ->
      if (!keepAudio || track.content == TrackContent.Video) return@mapNotNull null
      val media =
        composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid)
          ?: return@mapNotNull null
      LaidAudio(media, track.layOnto(media, AVMediaTypeAudio, assets, output.frameRate, onGrid = false), index)
    }

  if (placements.isEmpty() && mixed.all { it.placements.isEmpty() }) {
    throw AvLoweringFailure(NOTHING_INSERTED)
  }
  // The composition's audio effects run over the whole timeline, whatever audio the tracks lay under it. A player
  // can be handed them later without a new composition, so one that runs any audio effect lays the silence too.
  val silence =
    if (keepAudio && (mixAudioEffects.isNotEmpty() || (plays && hasAudioEffects))) {
      composition.laySilence(duration, pcmReaderFormat(output), bytesRoot)?.let { LaidAudio(it, emptyList(), null) }
    } else {
      null
    }

  val spans = placements.toSpans(composition.duration.toDuration(), output.size, layoutSize, hdrTransfer)
  val chain = if (keepVideo) CoreImageChain(this, spans, encodesHdr) else null
  val timedByGrid = laysVideoOnGrid && placements.isNotEmpty()
  // An audio-only clip can bring a grade into the plan that no laid video carries, which leaves nothing to tone map.
  val toneMaps = hdr == ResolvedHdr.ToneMap && spans.any { it.sourceTransfer != null }
  val holdsSd = spans.any { it.sdColor != null }
  // A still's picture is swapped in by the chain, so only a video with alpha needs the compositor's BGRA frames.
  val holdsAlpha = spans.any { it.hasAlpha && it.still == null }
  val drawnByCompositor = chain != null && (toneMaps || holdsSd || holdsAlpha)
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
    encodesHdr = encodesHdr,
    transfer = hdrTransfer,
    laidSpans = spans,
    chain = chain,
    drawnByCompositor = drawnByCompositor,
    timedByGrid = timedByGrid,
    audio = AvAudioGraph(mixed + listOfNotNull(silence), this, plays),
    stretched = stretched.takeIf { plays },
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
 * @param timedByGrid Whether the frame grid rather than the source track says when frames fall, which every plan laying
 * its video on the grid needs, since each slot's segment holds the frame nearest it and not the source's own timing.
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
