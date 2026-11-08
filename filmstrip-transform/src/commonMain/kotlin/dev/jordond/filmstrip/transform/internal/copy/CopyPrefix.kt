package dev.jordond.filmstrip.transform.internal.copy

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.negotiation.Phase
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.videoSlots
import dev.jordond.filmstrip.transform.internal.videoSlotsOf
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * One packet of a source's video stream, as a backend reads it in decode order.
 *
 * @property pts When the packet's frame is presented, on the source's own timeline. [Duration.INFINITE] for a packet
 * the container gave no time, which reads the whole listing as [CopyPrefix.Untimed].
 * @property key Whether the packet is a sync sample.
 */
@InternalFilmstripApi
public class CopyPacket(
  public val pts: Duration,
  public val key: Boolean,
)

/**
 * How far past a copy's end a backend reads the packets it hands [copyPrefixOf].
 *
 * A frame presented just before the end can decode after references presented past it, so the read runs on far enough
 * to reach every one of them. The bound is on what a backend reads, never on the count the packets give.
 */
@InternalFilmstripApi
public val COPY_PACKET_MARGIN: Duration = 1.seconds

/**
 * The highest frame rate a backend reads a copy's packets at.
 *
 * Above the 960 fps the fastest slow motion capture records, so no real source reaches it. It holds the read of a
 * source that lists far more packets than its window could carry to a bounded size.
 */
@InternalFilmstripApi
public const val MAX_COPY_PACKET_RATE: Int = 1_000

/**
 * How much of a source a read of the window from [from] to [to] covers, which is that window and its
 * [COPY_PACKET_MARGIN].
 */
@InternalFilmstripApi
public fun copyPacketSpan(
  from: Duration,
  to: Duration,
): Duration = to + COPY_PACKET_MARGIN - from

/**
 * How many packets a read of the window from [from] to [to] lists at most, which is its [copyPacketSpan] at
 * [MAX_COPY_PACKET_RATE]. A backend refuses a copy whose read lists more.
 */
@InternalFilmstripApi
public fun copyPacketCap(
  from: Duration,
  to: Duration,
): Int = ceil(copyPacketSpan(from, to).toDouble(DurationUnit.SECONDS) * MAX_COPY_PACKET_RATE).toInt()

/**
 * What [copyPrefixOf] counts off a copy's packets.
 */
@InternalFilmstripApi
public sealed interface CopyPrefix {
  /**
   * The copy writes the first [count] packets from the sync sample it opens on, which is always at least one.
   *
   * @property runs The counted frames that sit next to each other in presentation order, earliest first. There is
   * more than one when a frame the copy leaves out is presented between frames it writes, which happens where the
   * references past the grid are presented after a frame that is not written.
   * @property shown The counted frames presented on the grid, in runs the same way as [runs]. A preview of the copy
   * shows these, since the references a copy writes past the grid fall outside the composition it previews.
   */
  public class Packets(
    public val count: Int,
    public val runs: List<CopyRun>,
    public val shown: List<CopyRun>,
  ) : CopyPrefix {
    /**
     * Where the counted video stops showing, which is the end of the last of [runs]. Null when the counted video runs
     * to the source's end.
     */
    public val end: Duration?
      get() = runs.last().end
  }

  /**
   * The listing is empty or a packet in it has no presentation time, so a copy that ends before its source does has
   * no frame it knows to stop on. The planner blocks such a copy with
   * [dev.jordond.filmstrip.export.CopyBlocker.SourceUntimed].
   */
  public data object Untimed : CopyPrefix

  /**
   * Every packet has a presentation time and none lands on the copy's grid, so the count would be zero and the copy
   * would write no video. The planner refuses such a copy with [NONE_ON_GRID_COPY].
   */
  public data object NoneOnGrid : CopyPrefix
}

/**
 * Why a copy whose packets [copyPrefixOf] read as [CopyPrefix.Untimed] is refused.
 */
@InternalFilmstripApi
public const val UNTIMED_COPY: String =
  "The source's video packets carry no presentation time, so a copy that ends before its source does has no frame " +
    "to stop on."

/**
 * Why a copy whose packets [copyPrefixOf] read as [CopyPrefix.NoneOnGrid] is refused.
 */
@InternalFilmstripApi
public const val NONE_ON_GRID_COPY: String =
  "None of the source's video packets is presented on the copy's grid, so the copy would write no video."

/**
 * How many packets a copy opening at [opensAt] writes, counted in decode order, so every frame on the first
 * [videoSlots] slots of the grid at [frameRate] is written.
 *
 * [packets] is the source's video stream in decode order, starting on or before the sync sample at [opensAt] and read
 * at least [COPY_PACKET_MARGIN] past the window's end. The count starts on that sync sample and runs to the last
 * packet whose frame lands on one of the slots. A frame presented past the grid that decodes ahead of one on it is a
 * reference the earlier frame needs, so it is written too. That reorder residue is at most the codec's reorder depth,
 * and is zero for a stream with no B-frames or a grid ending on a whole reorder group.
 *
 * [CopyPrefix.Untimed] when the listing is empty or any packet has no presentation time, and [CopyPrefix.NoneOnGrid]
 * when none lands on the grid, so a copy never writes a count of zero. The counted frames are split into
 * [CopyPrefix.Packets.runs] by [copyPresentationRunsOf].
 */
@InternalFilmstripApi
public fun copyPrefixOf(
  packets: List<CopyPacket>,
  opensAt: Duration,
  videoSlots: Long,
  frameRate: Int,
): CopyPrefix {
  if (packets.lacksPresentationTimes()) return CopyPrefix.Untimed
  val opening = packets.indexOfLast { it.key && it.pts <= opensAt }.coerceAtLeast(0)
  val last =
    (packets.lastIndex downTo opening).firstOrNull { index ->
      val pts = packets[index].pts
      pts >= opensAt && outputSlotOf(pts - opensAt, frameRate) < videoSlots
    } ?: return CopyPrefix.NoneOnGrid
  val count = last + 1 - opening
  val listed = packets.subList(opening, packets.size)
  val onGrid = { pts: Duration -> outputSlotOf(pts - opensAt, frameRate) < videoSlots }
  return CopyPrefix.Packets(
    count = count,
    runs = copyPresentationRunsOf(listed, count, opensAt),
    shown = copyPresentationRunsOf(listed, count, opensAt, onGrid),
  )
}

/**
 * A stretch of a copy's counted frames with no frame the copy leaves out presented inside it, on the source's
 * timeline.
 *
 * @property start When its first frame starts showing, never before the copy opens.
 * @property lastFrameAt When its last frame starts showing.
 * @property end When the first listed frame presented after it is, which is a frame the copy leaves out. Null when the
 * listing holds no such frame, so the run shows to the source's end.
 */
@InternalFilmstripApi
public class CopyRun(
  public val start: Duration,
  public val lastFrameAt: Duration,
  public val end: Duration?,
)

/**
 * Splits the first [count] of [listed] into runs that are contiguous in presentation order, so a backend can show
 * exactly the frames a copy writes.
 *
 * [listed] is a copy's packets in decode order from the sync sample it opens on. A frame presented before [opensAt]
 * never shows, so a run ending by then is dropped and the first one kept starts on [opensAt] at the earliest. A
 * counted frame whose time [kept] refuses is left out the same way as one past the count.
 */
internal fun copyPresentationRunsOf(
  listed: List<CopyPacket>,
  count: Int,
  opensAt: Duration,
  kept: (Duration) -> Boolean = { true },
): List<CopyRun> {
  val presented = listed.withIndex().sortedBy { it.value.pts }
  val runs = mutableListOf<CopyRun>()
  var first: Duration? = null
  var latest = Duration.ZERO
  presented.forEach { (index, packet) ->
    if (index < count && kept(packet.pts)) {
      if (first == null) first = packet.pts
      latest = packet.pts
    } else {
      first?.let { runs += CopyRun(it, latest, packet.pts) }
      first = null
    }
  }
  first?.let { runs += CopyRun(it, latest, null) }
  return runs
    .filter { run -> run.end.let { it == null || it > opensAt } }
    .map { CopyRun(maxOf(it.start, opensAt), maxOf(it.lastFrameAt, opensAt), it.end) }
}

/**
 * How many video packets this copy writes, from [copyPrefixOf] over its one clip's [packets].
 *
 * The clip opens on [dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip.start] and the grid is
 * [videoSlots] long at the output's frame rate, the count a preview of the same plan shows.
 */
@InternalFilmstripApi
public fun ResolvedComposition.copyPrefixOf(packets: List<CopyPacket>): CopyPrefix {
  val clip = tracks.first().clips.first()
  return copyPrefixOf(packets, clip.start, videoSlots, output.frameRate)
}

/**
 * The prefix a copy over [tracks] writes, from [copyPrefixOf] over [packets] on the grid a composition of [duration]
 * at [frameRate] covers. Null when [path] is not a copy or no packets were read, so the copy writes its whole window.
 *
 * A listing that gives no count refuses the copy as an unreadable source, naming whether its packets carry no times or
 * none of them lands on the grid, since either would write no video.
 */
internal fun copyPrefixPhaseOf(
  packets: List<CopyPacket>?,
  path: ExportPath,
  tracks: List<ResolvedTrack>,
  duration: Duration,
  frameRate: Int,
): Phase<CopyPrefix.Packets?> {
  if (packets == null || path != ExportPath.Copy) return Phase.Next(null)
  val clip = tracks.first().clips.first()
  val reason =
    when (val prefix = copyPrefixOf(packets, clip.start, videoSlotsOf(duration, frameRate), frameRate)) {
      is CopyPrefix.Packets -> return Phase.Next(prefix)
      CopyPrefix.Untimed -> UNTIMED_COPY
      CopyPrefix.NoneOnGrid -> NONE_ON_GRID_COPY
    }
  return Phase.Refused(FilmstripError.SourceUnreadable(clip.source.describe(), reason))
}

/**
 * Whether a copy reading these packets cannot count its frames by them, which is an empty listing or one where any
 * packet carries no presentation time.
 */
internal fun List<CopyPacket>.lacksPresentationTimes(): Boolean = isEmpty() || any { !it.pts.isFinite() }

/**
 * The window of [source] a copy ending before its source does reads its video packets over, from the sync sample it
 * opens on at [from] to the end of its trim at [to].
 */
@InternalFilmstripApi
public class CopyPacketWindow(
  public val source: MediaSource,
  public val from: Duration,
  public val to: Duration,
)
