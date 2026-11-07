package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveInput
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * The protocol a live input reaches ffmpeg over, which every OS has and needs no extra descriptor in the child.
 */
internal const val LIVE_PROTOCOL: String = "tcp"

/**
 * The format a live input's frames are written in, luma from the plane and neutral chroma behind it.
 */
internal const val LIVE_PIXEL_FORMAT: String = "yuv420p"

/**
 * The most live inputs one graph opens, each a socket and a thread here and a demuxer in ffmpeg.
 *
 * Past this a plan draws its maps with its own graph, which gives the same samples and spends no descriptors, so a long
 * looping track cannot run a host out of them.
 */
internal const val MAX_LIVE_INPUTS: Int = 16

internal fun tooManyLiveInputs(count: Int): String? =
  if (count > MAX_LIVE_INPUTS) {
    "This plan reads $count live inputs and a graph opens at most $MAX_LIVE_INPUTS, so its looks draw their maps " +
      "with their own graphs."
  } else {
    null
  }

internal fun noLiveProtocol(): String =
  "This ffmpeg build has no $LIVE_PROTOCOL input protocol, so a look draws its map with its own graph."

/**
 * The filters this fragment's live form adds to a graph.
 */
internal fun FilterFragment.liveFilterNames(): Set<String> =
  buildSet {
    val form = live ?: return@buildSet
    addAll(LIVE_INPUT_FILTERS)
    form.branches.flatMap { it.nodes }.forEach { add(it.name.substringBefore('@')) }
    if (form.branches.any { it.writes.size > 1 }) add("split")
  }

private val LIVE_INPUT_FILTERS = setOf("setparams", "format", "settb", "setpts")

/**
 * The demuxer options that read one plane stream, or nothing for any other input.
 */
internal fun InputSpec.liveArguments(): List<String> {
  val input = (source as? InputSource.Live)?.input ?: return emptyList()
  return listOf(
    "-f",
    "rawvideo",
    "-pixel_format",
    LIVE_PIXEL_FORMAT,
    "-video_size",
    "${input.width}x${input.height}",
    "-framerate",
    input.ticksPerSecond.toString(),
  )
}

/**
 * The nodes that carry a live input's raw frames to the pad a fragment reads.
 *
 * Raw frames arrive untagged, and left that way ffmpeg converts them on the way into a tagged graph and moves a sample
 * by a code, so `setparams` names what they already are. Each frame is stamped on the first whole microsecond its tick
 * opens at, which is the clock two inputs of a filter are held against, whatever time base the frame reading it is on.
 *
 * A filter reading two pads settles one format for both, and a stream pinned without alpha would take the alpha off the
 * frame it lands on, which is what marks an unpainted bar. So the stream carries an opaque alpha plane in the graph.
 *
 * @param input The input being read.
 * @param index Where the input is declared, which names its start tick until a window is known.
 */
internal fun liveInputNodes(
  input: LiveInput,
  index: Int,
): List<FilterNode> =
  listOf(
    FilterNode("setparams", "range" to "tv", "colorspace" to "bt709"),
    FilterNode("format", "pix_fmts" to LIVE_GRAPH_FORMAT),
    FilterNode("settb", "expr" to "1/$LIVE_STAMP_BASE"),
    FilterNode("setpts", "expr" to input.tickOpening("N+${liveStartHolder(index)}")),
  )

/**
 * The first tick this input writes for a graph whose window opens at [windowOpensAt].
 *
 * A second ahead of the first frame that reads it, floored at the clock's zero. A stream that opens after the frame
 * reading it has nothing to hand over, and one that runs out repeats its last plane, neither with a word to anyone.
 */
internal fun InputSource.Live.startTick(windowOpensAt: Duration): Int {
  val opens = (origin + windowOpensAt).toDouble(DurationUnit.SECONDS)
  return (floor(opens * input.ticksPerSecond).toInt() - ceil(input.ticksPerSecond).toInt()).coerceAtLeast(0)
}

/**
 * This graph with each live input's start tick written in, which only a known window settles.
 */
internal fun String.withLiveStarts(
  inputs: List<InputSpec>,
  windowOpensAt: Duration,
): String =
  inputs.foldIndexed(this) { index, graph, input ->
    val live = input.source as? InputSource.Live
    if (live == null) graph else graph.replace(liveStartHolder(index), live.startTick(windowOpensAt).toString())
  }

private fun liveStartHolder(index: Int) = "<live-start-$index>"

private const val LIVE_GRAPH_FORMAT = "yuva420p"
private const val LIVE_STAMP_BASE = 1_000_000
