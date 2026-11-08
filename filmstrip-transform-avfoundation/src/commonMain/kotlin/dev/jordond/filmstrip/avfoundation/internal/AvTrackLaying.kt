package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.chain.ClipSpan
import dev.jordond.filmstrip.avfoundation.internal.chain.laysVideoOnGrid
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesKernelLimit
import dev.jordond.filmstrip.avfoundation.internal.chain.spanning
import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.sdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.still.stillSeed
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.ticksBetween
import dev.jordond.filmstrip.avfoundation.internal.time.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AppleScratch
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.CopyRun
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeCompare
import platform.CoreMedia.CMTimeConvertScale
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMaximum
import platform.CoreMedia.CMTimeMinimum
import platform.CoreMedia.CMTimeRangeFromTimeToTime
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMTimeSubtract
import platform.CoreMedia.kCMTimeRoundingMethod_RoundTowardZero
import platform.Foundation.NSURL
import kotlin.time.Duration

/**
 * One clip, once it has a slot on the composition timeline.
 */
internal class Placement(
  val clip: ResolvedClip,
  val start: Duration,
  val end: Duration,
  val heldFrom: Duration? = null,
  val sdColor: SdSourceColor? = null,
)

/**
 * Opens each source once, however many clips read from it and however many tracks each is laid
 * onto.
 *
 * @param drawsSd Whether the plan draws an SD clip's colour at all, which it does not when it encodes a grade. Every
 * clip reads as having no SD colour then, and nothing decodes a frame or needs the kernel.
 * @param stretched The audio an export already rendered to its clips' speeds, or null when a clip's audio track is
 * scaled instead, which is what a preview's player does.
 */
internal class AssetCache(
  private val bytesRoot: String = AppleScratch.defaultRoot,
  private val drawsSd: Boolean = true,
  private val stretched: StretchedAudio? = null,
  private val kernelLimit: () -> String? = { sdPrimariesKernelLimit() },
  private val readSdColor: (AVURLAsset, Int) -> SdSourceColor? = { asset, longestSide ->
    asset.sdSourceColor(longestSide)
  },
) {
  private val assets = mutableMapOf<MediaSource, AVURLAsset>()
  private val sdColors = mutableMapOf<MediaSource, SdSourceColor?>()

  fun of(source: MediaSource): AVURLAsset =
    assets.getOrPut(source) { AVURLAsset(uRL = source.toNSURL(bytesRoot), options = null) }

  /**
   * The track holding [clip]'s audio already at its speed, or null when its source is laid.
   */
  fun stretchedAudioOf(clip: ResolvedClip): AVAssetTrack? = stretched?.trackOf(clip)

  /**
   * The SD colour [clip]'s frames decode with, read once per source, or null for a still, a clip with no video, an HDR
   * clip and every clip of a plan that does not draw SD colour.
   *
   * @throws AvLoweringFailure When the clip has SD primaries and this device cannot run the kernel that moves them.
   */
  fun sdColorOf(clip: ResolvedClip): SdSourceColor? {
    val video = clip.info.video
    if (!drawsSd || clip.source is MediaSource.Image || video == null || video.hdrTransfer != null) return null
    // Null is an answer here too, and a read of it can decode a frame, so it is cached the same as a colour.
    val color =
      if (clip.source in sdColors) {
        sdColors[clip.source]
      } else {
        readSdColor(of(clip.source), maxOf(video.displaySize.width, video.displaySize.height))
          .also { sdColors[clip.source] = it }
      }
    if (color?.primariesToBt709 != null) kernelLimit()?.let { throw AvLoweringFailure(it) }
    return color
  }
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
 * laid exactly runs no longer than [ResolvedClip.outputDuration], and one on the grid runs as far as
 * its last slot, which can reach past the source's last frame.
 *
 * Video on the grid has its frames drawn on the slots, so each clip goes through [insertShown] and every slot shows
 * the frame nearest its own source time, whatever the clip's speed and its source's rate.
 *
 * The last clip laid on the grid runs on over [tail] as well, to the slot the composition ends on,
 * holding the frame its own last slot shows. Its placement carries that slot's time as
 * [Placement.heldFrom].
 *
 * A copy's video track lays its one clip through [insertCopied] when the plan counted its packets, so it holds the
 * frames the copy writes rather than every frame of the clip's span.
 *
 * @param frameRate The output's frame rate, which the video track lays its grid and its stills at.
 * @param onGrid Whether this is a video track whose boundaries land on the output frame grid.
 * @param tail The output slots the primary's last frame is held over, empty when nothing outlasts it.
 * @param copied The runs of frames a copy's video track holds, from [CopyPrefix.Packets.runs], or null for any other
 * track or plan.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun ResolvedTrack.layOnto(
  media: AVMutableCompositionTrack,
  mediaType: String?,
  assets: AssetCache,
  frameRate: Int,
  onGrid: Boolean,
  tail: LongRange = LongRange.EMPTY,
  copied: List<CopyRun>? = null,
): List<Placement> {
  val video = mediaType == AVMediaTypeVideo
  val grid = frameRate.takeIf { video && onGrid }
  val copy = copied.takeIf { video && grid == null }
  if (start > Duration.ZERO) media.insertEmptyTimeRange(timeRangeOf(Duration.ZERO, start))
  val holding =
    if (grid == null || tail.isEmpty()) -1 else clips.indexOfLast { !outputSlotsOf(it.span, grid).isEmpty() }

  return clips.mapIndexedNotNull { index, clip ->
    // The slot this clip holds, read off the plan. The planner derives it once so an effect reading
    // the time measures against the same run on every backend, and a looping track's later passes
    // carry their own.
    val at: Duration
    val end: Duration
    var heldFrom: Duration? = null
    var slots = LongRange.EMPTY
    if (grid == null) {
      at = clip.span.start
      end = at + (copy?.let { copiedRunOf(clip, it) } ?: clip.outputDuration)
    } else {
      val covered = outputSlotsOf(clip.span, grid)
      if (covered.isEmpty()) return@mapIndexedNotNull null
      val closing = if (index == holding) tail.last + 1 else covered.last + 1
      slots = covered.first until closing
      // Read back off the tick each end is laid on, so at a rate the timescale does not divide the span
      // still opens and closes where its segment does, and a frame at the seam finds its own clip.
      at = outputSlotTime(covered.first, grid).toCMTime().toDuration()
      end = outputSlotTime(closing, grid).toCMTime().toDuration()
      if (index == holding) heldFrom = outputSlotTime(tail.first - 1, grid)
    }
    val sdColor = if (video) assets.sdColorOf(clip) else null
    Placement(clip = clip, start = at, end = end, heldFrom = heldFrom, sdColor = sdColor).also {
      media.insertClip(it, mediaType, assets, frameRate, slots, copy)
    }
  }
}

/**
 * How long a copy's one [clip] runs on the video track: to where the last of [copied] stops showing, or over the
 * clip's own run when it stops short of that, since the last frame is then held to the end.
 */
internal fun copiedRunOf(
  clip: ResolvedClip,
  copied: List<CopyRun>,
): Duration = maxOf((copied.last().end ?: clip.info.duration) - clip.start, clip.outputDuration)

/**
 * Lays [placement]'s clip onto this track over the slot it was given, or empty time over that slot when nothing of the
 * clip can be laid there.
 *
 * A still is cut from [stillSeed] on the video track and is empty time on any other. A clip whose source has no track
 * of [mediaType] is empty time too. Audio an export already stretched goes in unscaled from the start of its own file,
 * which already runs for the slot. Video on the grid goes through [insertShown], and a copy's counted video through
 * [insertCopied]. Off the grid the resolved span is laid as it is.
 *
 * @param slots The output slots the placement runs over, empty off the grid.
 * @param copied The runs of frames a copy's video holds, or null when the clip is laid any other way.
 * @throws AvLoweringFailure When video on the grid can't be laid.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun AVMutableCompositionTrack.insertClip(
  placement: Placement,
  mediaType: String?,
  assets: AssetCache,
  frameRate: Int,
  slots: LongRange,
  copied: List<CopyRun>?,
) {
  val grid = frameRate.takeIf { !slots.isEmpty() }
  val clip = placement.clip
  val at = placement.start
  // On the grid both ends are rounded onto a tick and the length is what lies between, so the next
  // clip opens on the tick this one ends on. A length rounded on its own lands a tick either side of
  // that at 48 fps, which leaves a hole or an overlap at the seam.
  val length = if (grid == null) clip.outputDuration.toCMTime() else ticksBetween(at, placement.end)
  val still = clip.source is MediaSource.Image
  val source =
    if (still) null else assets.of(clip.source).tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack
  val stretched = if (mediaType == AVMediaTypeAudio) assets.stretchedAudioOf(clip) else null
  val inserted =
    when {
      still && mediaType == AVMediaTypeVideo -> {
        insertStill(at, placement.end, frameRate)
        true
      }
      stretched != null -> {
        insertTimeRange(
          CMTimeRangeMake(start = Duration.ZERO.toCMTime(), duration = length),
          stretched,
          at.toCMTime(),
          null,
        )
      }
      source == null -> {
        false
      }
      grid != null -> {
        insertShown(source, clip, slots, grid)
        true
      }
      copied != null -> {
        insertCopied(source, clip, at, copied)
      }
      else -> {
        insertScaled(source, clip.start, at, length, clip.speed)
      }
    }
  if (!inserted) insertEmptyTimeRange(CMTimeRangeMake(start = at.toCMTime(), duration = length))
}

/**
 * Lays a copy's [clip] onto this track at [at] as one segment per run of [copied], so the track holds exactly the
 * frames the copy writes and a preview of it shows the export's frames.
 *
 * Each run sits where its frames sit on the source's timeline. A frame the copy leaves out between two runs is not
 * laid, and the last frame before it is stretched up to the next run instead, which is what a player of the written
 * file shows, since each frame there stays up until the next one written. Video that stops showing before the clip's
 * own run ends has its last frame stretched over the rest the same way, so the picture holds under the audio rather
 * than going black.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertCopied(
  source: AVAssetTrack,
  clip: ResolvedClip,
  at: Duration,
  copied: List<CopyRun>,
): Boolean {
  copied.forEachIndexed { index, run ->
    // Each run goes in where the one before it ended, so a tick of rounding never opens an empty edit between them.
    val laidAt = if (index == 0) at.toCMTime() else CMTimeRangeGetEnd(timeRange)
    val shown = (run.end ?: clip.info.duration) - run.start
    if (!insertTimeRange(timeRangeOf(run.start, shown), source, laidAt, null)) return false
    val holdsTo = at + (copied.getOrNull(index + 1)?.start?.minus(clip.start) ?: clip.outputDuration)
    val lastFrame = (at + run.lastFrameAt - clip.start).toCMTime()
    val laidEnd = CMTimeRangeGetEnd(timeRange)
    if (CMTimeCompare(laidEnd, holdsTo.toCMTime()) < 0) {
      scaleTimeRange(
        CMTimeRangeFromTimeToTime(lastFrame, laidEnd),
        toDuration = CMTimeSubtract(holdsTo.toCMTime(), lastFrame),
      )
    }
  }
  return true
}

/**
 * Lays [source], read from [from], onto this track at [at] so it runs for [length] at [speed].
 *
 * A source that ends before the window [length] plays takes the time it has at [speed] and leaves the rest empty, so
 * the
 * clip after it still opens on its own slot. At a speed of 1 the range goes in as it is and reaching past the source's
 * end is left to AVFoundation.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertScaled(
  source: AVAssetTrack,
  from: Duration,
  at: Duration,
  length: CValue<CMTime>,
  speed: Double,
): Boolean {
  val atTime = at.toCMTime()
  if (speed == 1.0) {
    return insertTimeRange(CMTimeRangeMake(start = from.toCMTime(), duration = length), source, atTime, null)
  }
  val window = sourceTicksOf(length, speed)
  val available = CMTimeMinimum(window, ticksToSourceEnd(source, from))
  if (CMTimeCompare(available, CMTimeMake(value = 1, timescale = MEDIA_TIMESCALE)) < 0) return false
  if (!insertTimeRange(
      CMTimeRangeMake(start = from.toCMTime(), duration = available),
      source,
      atTime,
      null,
    )
  ) {
    return false
  }
  val played = if (CMTimeCompare(available, window) < 0) playedTicksOf(available, speed, length) else length
  scaleTimeRange(CMTimeRangeMake(start = atTime, duration = available), toDuration = played)
  if (CMTimeCompare(played, length) < 0) {
    insertEmptyTimeRange(
      CMTimeRangeMake(start = CMTimeAdd(atTime, played), duration = CMTimeSubtract(length, played)),
    )
  }
  return true
}

// The source a track length plays at speed, in whole ticks, since a segment can only be inserted in those.
@OptIn(ExperimentalForeignApi::class)
private fun sourceTicksOf(
  length: CValue<CMTime>,
  speed: Double,
): CValue<CMTime> = if (speed == 1.0) length else sourceLengthOf(length.toDuration(), speed).toCMTime()

// The track time whole ticks of source play for at speed, held to at least a tick and at most [ceiling].
@OptIn(ExperimentalForeignApi::class)
private fun playedTicksOf(
  source: CValue<CMTime>,
  speed: Double,
  ceiling: CValue<CMTime>,
): CValue<CMTime> =
  if (speed == 1.0) {
    source
  } else {
    CMTimeMinimum(
      CMTimeMaximum(outputLengthOf(source.toDuration(), speed).toCMTime(), CMTimeMake(1, MEDIA_TIMESCALE)),
      ceiling,
    )
  }

@OptIn(ExperimentalForeignApi::class)
private fun ticksToSourceEnd(
  source: AVAssetTrack,
  from: Duration,
): CValue<CMTime> =
  CMTimeConvertScale(
    CMTimeSubtract(CMTimeRangeGetEnd(source.timeRange), from.toCMTime()),
    MEDIA_TIMESCALE,
    kCMTimeRoundingMethod_RoundTowardZero,
  )

/**
 * Gives a still the slot from [at] until [end] on this track, as real segments cut from the seed
 * for [frameRate].
 *
 * @throws AvLoweringFailure when there is no seed to cut from or the track refuses one, which
 *   would otherwise leave the still holding no time and the export running short by however long
 *   the photo was to be held.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertStill(
  at: Duration,
  end: Duration,
  frameRate: Int,
) {
  val seed = stillSeed(frameRate) ?: throw AvLoweringFailure(NO_STILL_SEED)
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
    if (!inserted) throw AvLoweringFailure(NO_STILL_SEED)
    cursor += piece
  }
}

/**
 * Turns placements into spans that tile the timeline exactly.
 *
 * Each span's end is taken from the next one's start and the last reaches the composition's real
 * duration, so rounding inside one clip cannot open a gap in front of the next.
 */
internal fun List<Placement>.toSpans(
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
      sdColor = placement.sdColor,
    )
  }

/**
 * The slots [track] holds its last frame over, when it is the primary laid on the grid.
 *
 * Empty for any other track.
 */
internal fun ResolvedComposition.tailHeldBy(track: ResolvedTrack): LongRange =
  if (laysVideoOnGrid && track === tracks.first()) heldTail else LongRange.EMPTY

private const val NO_STILL_SEED =
  "A still needs a segment of its own on the timeline, and AVFoundation would neither write nor " +
    "accept one, so the export would run short by however long the photo was to be held."

/**
 * The URL AVFoundation opens this source through.
 */
internal fun MediaSource.toNSURL(bytesRoot: String = AppleScratch.defaultRoot): NSURL =
  when (this) {
    is MediaSource.Path -> {
      NSURL.fileURLWithPath(path)
    }
    is MediaSource.Uri -> {
      NSURL.URLWithString(uri) ?: throw AvLoweringFailure("$uri is not a URL AVFoundation can open.")
    }
    is MediaSource.Bytes -> {
      AppleScratch.urlFor(this, bytesRoot)
    }
    is MediaSource.Image -> {
      throw AvLoweringFailure(
        "${image.describe()} is a still, which takes its slot from a generated segment and is " +
          "drawn by the filter handler rather than opened as an asset.",
      )
    }
  }
