package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.GainSegment
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.heldTail
import kotlinx.cinterop.CValue
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
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.videoCompositionWithAsset
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeCompare
import platform.CoreMedia.CMTimeConvertScale
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMinimum
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMTimeSubtract
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreMedia.kCMTimeRoundingMethod_RoundTowardZero
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
 * @property heldFrom The time every frame from here to the span's end is drawn at, or null when each
 * frame is drawn at its own. Set on the primary's last span when an audio track outlasts it, to the
 * last slot the primary covers, so the held tail repeats that slot's frame, effects included.
 */
internal class ClipSpan(
  val start: Duration,
  val end: Duration,
  val rotationDegrees: Int,
  val attributes: Attributes,
  val effects: List<ResolvedEffect>,
  val still: ImageSource? = null,
  val heldFrom: Duration? = null,
) {
  fun covers(time: Duration): Boolean = time in start..<end
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
public class AvComposition internal constructor(
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
  internal val spans: List<ClipSpan> get() = chain?.spans ?: laidSpans

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
  val tail = videoTrack?.let { tailHeldBy(it) } ?: LongRange.EMPTY

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
    throw AppleLoweringFailure(NOTHING_INSERTED)
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
  val heldFrom: Duration? = null,
)

/**
 * Opens each source once, however many clips read from it and however many tracks each is laid
 * onto.
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
 * A video track [onGrid] lays every boundary on the output frame grid at [frameRate]. A clip opens
 * on the slot its span's start lands on and runs to the slot its end lands on, so each frame the
 * reader writes sits on a slot and one clip's last frame meets the next clip's first. A clip covering
 * no slot lays nothing and takes no placement. Any other track lays each clip over exactly the span
 * the plan resolved it to, which for audio is what the mix and each gain curve are measured against.
 *
 * Each clip opens at the source time the plan resolved it to. A cut that moved back to open a copy
 * on a sync sample has already moved by the time it arrives here, so nothing is re-derived. A clip
 * laid exactly runs no longer than [ResolvedClip.duration], and one on the grid runs as far as its
 * last slot, which can reach past the source's last frame. [insertHolding] holds that frame until
 * the slot ends.
 *
 * The last clip laid on the grid runs on over [tail] as well, to the slot the composition ends on,
 * holding the frame its own last slot shows. Its placement carries that slot's time as
 * [Placement.heldFrom].
 *
 * @param frameRate The output's frame rate, which the video track lays its grid and its stills at.
 * @param onGrid Whether this is a video track whose boundaries land on the output frame grid.
 * @param tail The output slots the primary's last frame is held over, empty when nothing outlasts it.
 * @throws AppleLoweringFailure when the video track has no frame rate to lay its clips at.
 */
@OptIn(ExperimentalForeignApi::class)
private fun ResolvedTrack.layOnto(
  media: AVMutableCompositionTrack,
  mediaType: String?,
  assets: AssetCache,
  frameRate: Int?,
  onGrid: Boolean,
  tail: LongRange = LongRange.EMPTY,
): List<Placement> {
  val video = mediaType == AVMediaTypeVideo
  val grid =
    if (video && onGrid) {
      frameRate?.takeIf { it > 0 } ?: throw AppleLoweringFailure(NO_FRAME_RATE)
    } else {
      null
    }
  if (start > Duration.ZERO) media.insertEmptyTimeRange(timeRangeOf(Duration.ZERO, start))
  val holding =
    if (grid == null || tail.isEmpty()) -1 else clips.indexOfLast { !outputSlotsOf(it.span, grid).isEmpty() }

  return clips.mapIndexedNotNull { index, clip ->
    // The slot this clip holds, read off the plan rather than accumulated here a second time. The
    // planner derives it once so an effect reading the time measures against the same run on every
    // backend, and a looping track's later passes carry their own.
    val at: Duration
    val end: Duration
    var heldFrom: Duration? = null
    if (grid == null) {
      at = clip.span.start
      end = at + clip.duration
    } else {
      val slots = outputSlotsOf(clip.span, grid)
      if (slots.isEmpty()) return@mapIndexedNotNull null
      // Read back off the tick each end is laid on, so at a rate the timescale does not divide the span
      // still opens and closes where its segment does, and a frame at the seam finds its own clip.
      at = outputSlotTime(slots.first, grid).toCMTime().toDuration()
      val closing = if (index == holding) tail.last + 1 else slots.last + 1
      end = outputSlotTime(closing, grid).toCMTime().toDuration()
      if (index == holding) heldFrom = outputSlotTime(tail.first - 1, grid)
    }
    // On the grid both ends are rounded onto a tick and the length is what lies between, so the next
    // clip opens on the tick this one ends on. A length rounded on its own lands a tick either side of
    // that at 48 fps, which leaves a hole or an overlap at the seam.
    val length = if (grid == null) clip.duration.toCMTime() else ticksBetween(at, end)
    val still = clip.source is MediaSource.Image
    val inserted =
      when {
        still && video -> {
          media.insertStill(at, end, frameRate)
          true
        }
        still -> {
          false
        }
        else -> {
          val source = assets.of(clip.source).tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack
          when {
            source == null -> {
              false
            }
            grid != null -> {
              media.insertHolding(source, clip.start, at, length, heldFrom)
            }
            else -> {
              media.insertTimeRange(
                timeRange = CMTimeRangeMake(start = clip.start.toCMTime(), duration = length),
                ofTrack = source,
                atTime = at.toCMTime(),
                error = null,
              )
            }
          }
        }
      }
    if (!inserted) media.insertEmptyTimeRange(CMTimeRangeMake(start = at.toCMTime(), duration = length))

    Placement(clip = clip, start = at, end = end, heldFrom = heldFrom)
  }
}

/**
 * Lays [length] of [source], read from [from], onto this track at [at], holding the source's last
 * frame over any part of that which runs past the end of its track.
 *
 * AVFoundation takes a range reaching past the end of a track without complaint, and the reader
 * then draws an empty frame from where the track runs out until the segment ends. The range is cut
 * at the track's end instead, in whole ticks so none of it is past the end, and its final tick is
 * stretched over the rest of the length. That shows the last frame until the length runs out and
 * starts no frame of its own where the track did.
 *
 * With [heldFrom] set the range is cut one tick after that time as well, whichever comes first, so
 * the frame shown there is the one stretched to the end of the length.
 *
 * @param heldFrom The time on this track whose frame is held to the end of the length, or null to
 * read the source for as long as it lasts.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertHolding(
  source: AVAssetTrack,
  from: Duration,
  at: Duration,
  length: CValue<CMTime>,
  heldFrom: Duration? = null,
): Boolean {
  val tick = CMTimeMake(value = 1, timescale = MEDIA_TIMESCALE)
  val toSourceEnd =
    CMTimeConvertScale(
      CMTimeSubtract(CMTimeRangeGetEnd(source.timeRange), from.toCMTime()),
      MEDIA_TIMESCALE,
      kCMTimeRoundingMethod_RoundTowardZero,
    )
  val held = heldFrom?.let { CMTimeMinimum(toSourceEnd, CMTimeAdd(ticksBetween(at, it), tick)) } ?: toSourceEnd
  val short = CMTimeCompare(held, length) < 0 && CMTimeCompare(held, tick) >= 0
  val inserted =
    insertTimeRange(
      timeRange = CMTimeRangeMake(start = from.toCMTime(), duration = if (short) held else length),
      ofTrack = source,
      atTime = at.toCMTime(),
      error = null,
    )
  if (inserted && short) {
    val lastTick = CMTimeSubtract(CMTimeAdd(at.toCMTime(), held), tick)
    scaleTimeRange(
      timeRange = CMTimeRangeMake(start = lastTick, duration = tick),
      toDuration = CMTimeSubtract(CMTimeAdd(at.toCMTime(), length), lastTick),
    )
  }
  return inserted
}

/**
 * Gives a still the slot from [at] until [end] on this track, as real segments cut from the seed
 * for [frameRate].
 *
 * @throws AppleLoweringFailure when there is no seed to cut from or the track refuses one, which
 *   would otherwise leave the still holding no time and the export running short by however long
 *   the photo was to be held.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertStill(
  at: Duration,
  end: Duration,
  frameRate: Int?,
) {
  val seed =
    frameRate
      ?.takeIf { it > 0 }
      ?.let(::stillSeed)
      ?: throw AppleLoweringFailure(NO_STILL_SEED)
  var cursor = at

  // Cut to what the seed holds rather than to STILL_SEED_LENGTH. A seed left by an earlier run can
  // be a frame short, and AVFoundation answers a cut longer than the track by clamping it and still
  // reporting success, which would advance the cursor past time the track never got.
  while (cursor < end) {
    val piece = minOf(end - cursor, seed.length)
    val inserted =
      insertTimeRange(
        timeRange = CMTimeRangeMake(start = Duration.ZERO.toCMTime(), duration = ticksBetween(cursor, cursor + piece)),
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
      heldFrom = placement.heldFrom,
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
 * The plan holds one clip per pass and [layOnto] lays one placement per clip, leaving out a clip
 * that covers no output slot when it lays on the grid. A span and the clip behind it line up by
 * index once those are left out here as well.
 *
 * @throws AppleLoweringFailure when [resolved] lays its video on the grid and names no frame rate to
 *   find its clips' slots at, the same way [layOnto] fails for it.
 */
internal fun List<ClipSpan>.respannedOnto(resolved: ResolvedComposition): List<ClipSpan> {
  val laid = resolved.videoTrack?.clips.orEmpty()
  if (laid.isEmpty()) return emptyList()
  val clips =
    if (resolved.laysVideoOnGrid) {
      val frameRate = resolved.output.frameRate?.takeIf { it > 0 } ?: throw AppleLoweringFailure(NO_FRAME_RATE)
      laid.filterNot { outputSlotsOf(it.span, frameRate).isEmpty() }
    } else {
      laid
    }
  if (clips.isEmpty()) return emptyList()
  require(size == clips.size) {
    "This composition holds $size spans and the swap lays ${clips.size} clips, so a span has no " +
      "clip of its own to be rebuilt from."
  }

  return mapIndexed { index, span ->
    clips[index].spanning(
      start = span.start,
      end = span.end,
      rotationDegrees = span.rotationDegrees,
      outputSize = resolved.output.size,
      layoutSize = resolved.layoutSize,
      hdrTransfer = resolved.hdrTransfer,
      heldFrom = span.heldFrom,
    )
  }
}

/**
 * The span this clip draws over [start] until [end].
 *
 * The one place a span's attributes are derived, so a clip laid down by [layOnto] and the same clip
 * swapped in by [respannedOnto] are measured against the same frame.
 */
private fun ResolvedClip.spanning(
  start: Duration,
  end: Duration,
  rotationDegrees: Int,
  outputSize: Size,
  layoutSize: Size,
  hdrTransfer: HdrTransfer?,
  heldFrom: Duration?,
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
    heldFrom = heldFrom,
  )
}

/**
 * Whether the video track lands every clip on the output frame grid.
 *
 * A copy writes the source's own samples rather than rendering frames, so it has no output grid to
 * land on and keeps the exact spans the plan resolved.
 */
private val ResolvedComposition.laysVideoOnGrid: Boolean
  get() = path != ExportPath.Transmux

/**
 * The slots [track] holds its last frame over, when it is the primary laid on the grid.
 *
 * Empty for any other track, and for a plan with no frame rate, which fails when the track is laid.
 */
private fun ResolvedComposition.tailHeldBy(track: ResolvedTrack): LongRange =
  if (laysVideoOnGrid && track === tracks.first() && (output.frameRate ?: 0) > 0) heldTail else LongRange.EMPTY

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
 * AVFoundation expresses a linear ramp natively and exactly, so a clip's curve is written straight
 * across as one call per [GainSegment] rather than approximated.
 */
@OptIn(ExperimentalForeignApi::class)
private fun List<Pair<AVMutableCompositionTrack, List<Placement>>>.toAudioMix(): AVMutableAudioMix? {
  if (all { (_, placements) -> placements.all { it.clip.gain.constant == 1f } }) return null

  val parameters =
    map { (media, placements) ->
      AVMutableAudioMixInputParameters().apply {
        setTrackID(media.trackID)
        placements.forEach { it.writeGain(this) }
      }
    }

  return AVMutableAudioMix().apply { setInputParameters(parameters) }
}

/**
 * Writes this placement's gain curve onto [parameters], in the composition time [parameters]
 * already reads.
 *
 * A constant curve is one `setVolumeRampFromStartVolume:toEndVolume:timeRange:` holding that level
 * at both ends of the placement's own span, so the level stays where it was written until the next
 * placement writes its own. A ramping curve is one ramp per [GainSegment], with the segment's
 * clip-relative `start`/`end` shifted onto the timeline by this placement's own [Placement.start],
 * since a curve is measured from zero at the clip's own start and AVFoundation only understands the
 * composition's time. A zero-length segment is a step rather than a ramp, and AVFoundation has no
 * ramp to hold across no time, so it is written as a plain `setVolume:atTime:` at the instant the
 * step lands on.
 */
@OptIn(ExperimentalForeignApi::class)
private fun Placement.writeGain(parameters: AVMutableAudioMixInputParameters) {
  val constant = clip.gain.constant
  if (constant != null) {
    parameters.setVolumeRampFromStartVolume(
      constant,
      toEndVolume = constant,
      timeRange = timeRangeOf(start, end - start),
    )
    return
  }

  clip.gain.segments.forEach { segment ->
    val segmentStart = start + segment.start
    val length = segment.end - segment.start
    if (length <= Duration.ZERO) {
      parameters.setVolume(segment.endGain, atTime = segmentStart.toCMTime())
    } else {
      parameters.setVolumeRampFromStartVolume(
        segment.startGain,
        toEndVolume = segment.endGain,
        timeRange = timeRangeOf(segmentStart, length),
      )
    }
  }
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
  output.frameRate?.takeIf { it > 0 }?.let { rate ->
    composition.setFrameDuration(CMTimeMake(value = 1, timescale = rate))
    if (holdsTail) composition.setSourceTrackIDForFrameTiming(kCMPersistentTrackID_Invalid)
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

private const val NO_FRAME_RATE =
  "The video track lays every clip on the output's frame grid, and this output names no frame rate " +
    "to lay it at."

private const val NO_STILL_SEED =
  "A still needs a segment of its own on the timeline, and AVFoundation would neither write nor " +
    "accept one, so the export would run short by however long the photo was to be held."

private const val NOTHING_INSERTED =
  "No clip in this composition contributed a track AVFoundation could read, so the export would " +
    "write a file with nothing in it."
