package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableAudioMixInputParameters
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVURLAsset
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
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.Foundation.NSURL
import kotlin.time.Duration

/**
 * Why a resolved composition could not be built into an AVFoundation one.
 *
 * Only reachable when a source survived planning that AVFoundation has no way to open, or when a
 * clip's track turns out not to be there once the asset is loaded.
 */
internal class AppleLoweringFailure(
  val reason: String,
) : Exception(reason)

/**
 * One clip's slot on the composition timeline, and what to draw on the frames inside it.
 *
 * The filter handler is given a composition time and nothing else, so the only way back to the clip
 * a frame came from is a range lookup. Spans tile the whole timeline exactly. Each one's end is the
 * next one's start and the last reaches the composition's duration. A gap or an overlap fails the
 * render with `AVErrorInvalidVideoComposition` and no indication of which span is wrong.
 *
 * @property rotationDegrees What the container asked a player to rotate by. Baked into the pixels
 *   here, since the flag that would apply it is ignored once a video composition is set.
 * @property attributes The frame this clip's effects were resolved against, which is the clip's own
 *   size, never the output's.
 * @property still The image every frame of this span draws, or null when the clip is video and what
 *   AVFoundation decoded is already the picture.
 */
@InternalFilmstripApi
public class ClipSpan(
  public val start: Duration,
  public val end: Duration,
  public val rotationDegrees: Int,
  public val attributes: Attributes,
  public val effects: List<ResolvedEffect>,
  public val still: ImageSource? = null,
) {
  public fun covers(time: Duration): Boolean = time in start..<end
}

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
public class AvComposition(
  public val composition: AVMutableComposition,
  public val videoComposition: AVMutableVideoComposition?,
  public val audioMix: AVMutableAudioMix?,
  public val encodesHdr: Boolean,
  public val transfer: HdrTransfer?,
  private val laidSpans: List<ClipSpan>,
  public val chain: CoreImageChain?,
) {
  /**
   * Where each clip sits on the timeline, and what it draws.
   *
   * Read off the chain wherever there is one, so a parameter swap shows here rather than leaving
   * this describing an edit that stopped rendering.
   */
  public val spans: List<ClipSpan> get() = chain?.spans ?: laidSpans

  /**
   * What the filter handler could not draw, or null when every frame went through.
   *
   * The handler cannot fail an export on its own. It runs inside AVFoundation, where an exception
   * terminates the process, so it records the failure and passes the frame on. The run asks once
   * the pumps have drained.
   */
  public fun chainFailure(): ExportError? = chain?.failure
}

/**
 * Builds the AVFoundation composition this plan runs as.
 *
 * The size stage is not a step in any clip's chain. Every clip is fitted to [OutputFormat.size]
 * inside the filter handler, after that clip's own effects and before the composition-level ones,
 * which is what lets clips of differing sizes concatenate. See [CoreImageChain].
 *
 * @throws AppleLoweringFailure when a source or a track cannot be opened.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public fun ResolvedComposition.toAvComposition(): AvComposition {
  val keepAudio = output.audioCodec != AudioCodec.None
  val keepVideo = audio != AudioSpec.AudioOnly
  val composition = AVMutableComposition()
  val assets = AssetCache()

  val placements =
    videoTrack
      ?.let { track ->
        val media =
          composition.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
            ?: throw AppleLoweringFailure(NO_VIDEO_TRACK)
        // The preferred transform is a player instruction, and a composition carrying a video
        // composition ignores it. Every clip bakes its own rotation instead, so this says the frames
        // are already the way round they should be shown.
        media.preferredTransform = CGAffineTransformIdentity.readValue()
        track.layOnto(media, AVMediaTypeVideo, duration, assets, output.frameRate)
      }.orEmpty()

  val mixed =
    tracks
      .filter { keepAudio && it.content != TrackContent.Video }
      .mapNotNull { track ->
        val media =
          composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid)
            ?: return@mapNotNull null
        media to track.layOnto(media, AVMediaTypeAudio, duration, assets, output.frameRate)
      }

  if (placements.isEmpty() && mixed.all { it.second.isEmpty() }) {
    throw AppleLoweringFailure(NOTHING_INSERTED)
  }

  val encodesHdr = hdrTransfer != null
  val spans = placements.toSpans(composition.duration.toDuration(), output.size, layoutSize, hdrTransfer)
  val chain = if (keepVideo) CoreImageChain(this, spans, encodesHdr) else null

  return AvComposition(
    composition = composition,
    videoComposition = chain?.let { composition.videoComposition(it, encodesHdr) },
    audioMix = mixed.toAudioMix(),
    encodesHdr = encodesHdr,
    transfer = hdrTransfer,
    laidSpans = spans,
    chain = chain,
  )
}

/**
 * The URL AVFoundation opens this source through.
 */
internal fun MediaSource.toNSURL(): NSURL =
  when (this) {
    is MediaSource.Path -> {
      NSURL.fileURLWithPath(path)
    }
    is MediaSource.Uri -> {
      NSURL.URLWithString(uri) ?: throw AppleLoweringFailure("$uri is not a URL AVFoundation can open.")
    }
    is MediaSource.Bytes -> {
      throw AppleLoweringFailure(
        "In-memory sources are written to a temporary file before encoding, which is not " +
          "implemented on Apple platforms.",
      )
    }
    is MediaSource.Image -> {
      throw AppleLoweringFailure(
        "${image.describe()} is a still, which takes its slot from a generated segment and is " +
          "drawn by the filter handler rather than opened as an asset.",
      )
    }
  }

/**
 * One clip, once it has a slot on the composition timeline.
 */
private class Placement(
  val clip: ResolvedClip,
  val start: Duration,
  val end: Duration,
)

/**
 * Opens each source once, however many clips read from it.
 */
private class AssetCache {
  private val assets = mutableMapOf<MediaSource, AVURLAsset>()

  fun of(source: MediaSource): AVURLAsset =
    assets.getOrPut(source) { AVURLAsset(uRL = source.toNSURL(), options = null) }
}

/**
 * Lays this track's clips onto [media], one after the next, and says where each landed.
 *
 * A clip whose source has no track of this type still takes its slot, as an empty range, so a
 * silent clip in the middle of a sequence shifts nothing after it. A still has no track of any type
 * and cannot hold its slot that way, since AVFoundation discards a trailing empty range, so on the
 * video track it takes a real segment cut from [stillSeed] and empty time everywhere else.
 *
 * @param stillFrameRate The cadence a still's segment is cut at, which only the video track reads.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalFilmstripApi::class)
private fun ResolvedTrack.layOnto(
  media: AVMutableCompositionTrack,
  mediaType: String?,
  limit: Duration,
  assets: AssetCache,
  stillFrameRate: Int?,
): List<Placement> {
  val placements = mutableListOf<Placement>()
  var cursor = Duration.ZERO

  if (start > Duration.ZERO) {
    media.insertEmptyTimeRange(timeRangeOf(Duration.ZERO, start))
    cursor = start
  }

  // A looping track is laid down again from the top until it covers everything a non-looping one
  // bounds, which is what `limit` already is.
  do {
    val passStart = cursor
    clips.forEach { clip ->
      val still = clip.source is MediaSource.Image
      val inserted =
        when {
          still && mediaType == AVMediaTypeVideo -> {
            media.insertStill(cursor, clip.duration, stillFrameRate)
            true
          }
          still -> {
            false
          }
          else -> {
            val source = assets.of(clip.source).tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack
            source != null &&
              media.insertTimeRange(
                timeRange = timeRangeOf(clip.start, clip.duration),
                ofTrack = source,
                atTime = cursor.toCMTime(),
                error = null,
              )
          }
        }
      if (!inserted) media.insertEmptyTimeRange(timeRangeOf(cursor, clip.duration))

      placements +=
        Placement(
          clip = clip,
          start = cursor,
          end = cursor + clip.duration,
        )
      cursor += clip.duration
    }
    // A pass that advances nothing would spin forever, and an empty clip list is reachable on an
    // audio track the planner left alone.
  } while (looping && cursor < limit && cursor > passStart)

  return placements
}

/**
 * Gives a still its slot on this track, as real segments cut from the seed for [frameRate].
 *
 * @throws AppleLoweringFailure when there is no seed to cut from or the track refuses one, which
 *   would otherwise leave the still holding no time and the export running short by however long
 *   the photo was to be held.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertStill(
  at: Duration,
  duration: Duration,
  frameRate: Int?,
) {
  val seed =
    frameRate
      ?.takeIf { it > 0 }
      ?.let(::stillSeed)
      ?: throw AppleLoweringFailure(NO_STILL_SEED)
  val end = at + duration
  var cursor = at

  // Cut to what the seed holds rather than to STILL_SEED_LENGTH. A seed left by an earlier run can
  // be a frame short, and AVFoundation answers a cut longer than the track by clamping it and still
  // reporting success, which would advance the cursor past time the track never got.
  while (cursor < end) {
    val piece = minOf(end - cursor, seed.length)
    val inserted =
      insertTimeRange(
        timeRange = timeRangeOf(Duration.ZERO, piece),
        ofTrack = seed.track,
        atTime = cursor.toCMTime(),
        error = null,
      )
    if (!inserted) throw AppleLoweringFailure(NO_STILL_SEED)
    cursor += piece
  }
}

/**
 * Turns placements into spans that tile the timeline exactly.
 *
 * Each span's end is taken from the next one's start and the last reaches the composition's real
 * duration, so rounding inside one clip cannot open a gap in front of the next.
 */
private fun List<Placement>.toSpans(
  compositionDuration: Duration,
  outputSize: Size,
  layoutSize: Size,
  hdrTransfer: HdrTransfer?,
): List<ClipSpan> =
  mapIndexed { index, placement ->
    val video = placement.clip.info.video
    placement.clip.spanning(
      start = placement.start,
      end = if (index == lastIndex) maxOf(compositionDuration, placement.end) else this[index + 1].start,
      rotationDegrees = video?.rotationDegrees ?: 0,
      outputSize = outputSize,
      layoutSize = layoutSize,
      hdrTransfer = hdrTransfer,
    )
  }

/**
 * Rebuilds these spans against [resolved]'s clips, over the slots they already hold.
 *
 * The slot a span covers and the rotation baked into it both come from where the clip was laid onto
 * the `AVMutableComposition`, and a parameters-only edit moves neither, so they are carried across
 * rather than derived again. Deriving them again would mean laying the whole composition down a
 * second time, which is the work a swap exists to avoid.
 *
 * A looping track lays its clips down more than once, so the clip behind a span is found by
 * wrapping round the list the same way [layOnto] wrapped when it produced the placements.
 */
internal fun List<ClipSpan>.respannedOnto(resolved: ResolvedComposition): List<ClipSpan> {
  val clips = resolved.videoTrack?.clips.orEmpty()
  if (clips.isEmpty()) return emptyList()

  return mapIndexed { index, span ->
    clips[index % clips.size].spanning(
      start = span.start,
      end = span.end,
      rotationDegrees = span.rotationDegrees,
      outputSize = resolved.output.size,
      layoutSize = resolved.layoutSize,
      hdrTransfer = resolved.hdrTransfer,
    )
  }
}

/**
 * The span this clip draws over [start] until [end].
 *
 * The one place a span's attributes are derived, so a clip laid down by [layOnto] and the same clip
 * swapped in by [respannedOnto] are measured against the same frame.
 */
@OptIn(ExperimentalFilmstripApi::class)
private fun ResolvedClip.spanning(
  start: Duration,
  end: Duration,
  rotationDegrees: Int,
  outputSize: Size,
  layoutSize: Size,
  hdrTransfer: HdrTransfer?,
): ClipSpan {
  val video = info.video
  return ClipSpan(
    start = start,
    end = end,
    rotationDegrees = rotationDegrees,
    attributes =
      Attributes(
        inputSize = video?.displaySize ?: outputSize,
        outputSize = outputSize,
        layoutSize = video?.displaySize ?: layoutSize,
        colorSpace = if (hdrTransfer != null) ColorSpace.Bt2020 else ColorSpace.Bt709,
        hdrTransfer = hdrTransfer,
        frameRate = video?.frameRate,
        // The planner's slot, not this lay-down's, so a clip effect is measured against the same
        // run media3 measures against rather than against a second walk of the same durations.
        span = span,
      ),
    effects = effects,
    still = (source as? MediaSource.Image)?.image,
  )
}

/**
 * The track whose frames reach the video composition, or null when the output carries no video.
 */
internal val ResolvedComposition.videoTrack: ResolvedTrack?
  get() =
    if (audio == AudioSpec.AudioOnly) {
      null
    } else {
      tracks.firstOrNull { it.content != TrackContent.Audio }
    }

/**
 * How long each clip the video composition draws from runs for.
 *
 * The shape the spans are laid out against, so two compositions agreeing on it tile the timeline
 * identically and the spans of one can be reused for the other.
 */
internal val ResolvedComposition.videoClipDurations: List<Duration>
  get() = videoTrack?.clips?.map { it.duration }.orEmpty()

/**
 * The mix that applies each clip's gain, or null when every clip is at full volume.
 *
 * A gain is a step at the clip's start, written as a ramp over a zero-length range.
 */
@OptIn(ExperimentalForeignApi::class)
private fun List<Pair<AVMutableCompositionTrack, List<Placement>>>.toAudioMix(): AVMutableAudioMix? {
  if (all { (_, placements) -> placements.all { it.clip.gain == 1f } }) return null

  val parameters =
    map { (media, placements) ->
      AVMutableAudioMixInputParameters().apply {
        setTrackID(media.trackID)
        placements.forEach { setVolume(it.clip.gain, atTime = it.start.toCMTime()) }
      }
    }

  return AVMutableAudioMix().apply { setInputParameters(parameters) }
}

/**
 * The video composition, with the filter handler already attached.
 *
 * Built through `videoCompositionWithAsset:applyingCIFiltersWithHandler:`, whose block bridges to a
 * Kotlin lambda. A custom `AVVideoCompositing` is an Objective-C class handed to AVFoundation,
 * which Kotlin/Native cannot register.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableComposition.videoComposition(
  chain: CoreImageChain,
  encodesHdr: Boolean,
): AVMutableVideoComposition {
  val output = chain.output
  val composition =
    AVMutableVideoComposition.videoCompositionWithAsset(this) { request ->
      request?.let(chain::render)
    }

  composition.setRenderSize(CGSizeMake(output.size.width.toDouble(), output.size.height.toDouble()))
  output.frameRate?.takeIf { it > 0 }?.let { rate ->
    composition.setFrameDuration(CMTimeMake(value = (MEDIA_TIMESCALE / rate).toLong(), timescale = MEDIA_TIMESCALE))
  }

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

private const val NO_STILL_SEED =
  "A still needs a segment of its own on the timeline, and AVFoundation would neither write nor " +
    "accept one, so the export would run short by however long the photo was to be held."

private const val NOTHING_INSERTED =
  "No clip in this composition contributed a track AVFoundation could read, so the export would " +
    "write a file with nothing in it."
