package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.chain.ClipSpan
import dev.jordond.filmstrip.avfoundation.internal.chain.laysVideoOnGrid
import dev.jordond.filmstrip.avfoundation.internal.chain.spanning
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.still.stillSeed
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.ticksBetween
import dev.jordond.filmstrip.avfoundation.internal.time.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetTrack
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
import platform.CoreMedia.CMTimeMinimum
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
)

/**
 * Opens each source once, however many clips read from it and however many tracks each is laid
 * onto.
 */
internal class AssetCache {
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
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ResolvedTrack.layOnto(
  media: AVMutableCompositionTrack,
  mediaType: String?,
  assets: AssetCache,
  frameRate: Int,
  onGrid: Boolean,
  tail: LongRange = LongRange.EMPTY,
): List<Placement> {
  val video = mediaType == AVMediaTypeVideo
  val grid = frameRate.takeIf { video && onGrid }
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
    Placement(clip = clip, start = at, end = end, heldFrom = heldFrom).also {
      media.insertClip(it, mediaType, assets, frameRate, grid)
    }
  }
}

/**
 * Lays [placement]'s clip onto this track over the slot it was given, or empty time over that slot when nothing of the
 * clip can be laid there.
 *
 * A still is cut from [stillSeed] on the video track and is empty time on any other. A clip whose source has no track
 * of [mediaType] is empty time too. On the grid at [grid] the source goes through [insertHolding], and off it the
 * resolved span is laid as it is.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVMutableCompositionTrack.insertClip(
  placement: Placement,
  mediaType: String?,
  assets: AssetCache,
  frameRate: Int,
  grid: Int?,
) {
  val clip = placement.clip
  val at = placement.start
  // On the grid both ends are rounded onto a tick and the length is what lies between, so the next
  // clip opens on the tick this one ends on. A length rounded on its own lands a tick either side of
  // that at 48 fps, which leaves a hole or an overlap at the seam.
  val length = if (grid == null) clip.duration.toCMTime() else ticksBetween(at, placement.end)
  val still = clip.source is MediaSource.Image
  val source =
    if (still) null else assets.of(clip.source).tracksWithMediaType(mediaType).firstOrNull() as? AVAssetTrack
  val inserted =
    when {
      still && mediaType == AVMediaTypeVideo -> {
        insertStill(at, placement.end, frameRate)
        true
      }
      source == null -> {
        false
      }
      grid != null -> {
        insertHolding(source, clip.start, at, length, placement.heldFrom)
      }
      else -> {
        insertTimeRange(
          timeRange = CMTimeRangeMake(start = clip.start.toCMTime(), duration = length),
          ofTrack = source,
          atTime = at.toCMTime(),
          error = null,
        )
      }
    }
  if (!inserted) insertEmptyTimeRange(CMTimeRangeMake(start = at.toCMTime(), duration = length))
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
internal fun MediaSource.toNSURL(): NSURL =
  when (this) {
    is MediaSource.Path -> {
      NSURL.fileURLWithPath(path)
    }
    is MediaSource.Uri -> {
      NSURL.URLWithString(uri) ?: throw AvLoweringFailure("$uri is not a URL AVFoundation can open.")
    }
    is MediaSource.Bytes -> {
      throw AvLoweringFailure(
        "In-memory sources are written to a temporary file before encoding, which is not " +
          "implemented on Apple platforms.",
      )
    }
    is MediaSource.Image -> {
      throw AvLoweringFailure(
        "${image.describe()} is a still, which takes its slot from a generated segment and is " +
          "drawn by the filter handler rather than opened as an asset.",
      )
    }
  }
