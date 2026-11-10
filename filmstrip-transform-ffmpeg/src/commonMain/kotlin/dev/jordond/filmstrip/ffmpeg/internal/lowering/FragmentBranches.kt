package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effect.WINDOW_CLOCK
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Every filter this fragment puts in a graph by name, those the backend adds around it included.
 *
 * The planner refuses a fragment whose filters a build does not have, rather than handing ffmpeg a graph it fails on.
 */
internal fun FilterFragment.filterNames(): Set<String> =
  buildSet {
    val nodes =
      chain + listOfNotNull(merge) + post + branches.flatMap { it.nodes } + auxInputs.flatMap { it.chain }
    nodes.forEach { node ->
      // A name past an @ is that node's own instance name. The filter comes before it.
      if (node.name == PlaneLanes.NAME) addAll(PLANE_LANES_FILTERS) else add(node.name.substringBefore('@'))
    }
    if (branches.any { it.writes.size > 1 }) add("split")
    if (laysBranches) add(if (readsCompositionClock) "setpts" else "null")
    if (crop != null) addAll(CROPPED_FILTERS)
  }

/**
 * Whether the backend lays this fragment with [branches], which is every fragment with a graph of its own and every
 * chain that reads the composition clock.
 *
 * A clock reader with a merge or a crop runs its own way and keeps the timestamps it was handed.
 */
internal val FilterFragment.laysBranches: Boolean
  get() = branches.isNotEmpty() || (readsCompositionClock && merge == null && crop == null)

// What a plane lanes node and a cropped fragment are expanded to.
private const val CHAIN_PAD = "chain"
private val PLANE_LANES_FILTERS = setOf("format", "extractplanes", "mergeplanes")
private val CROPPED_FILTERS = setOf("split", "crop", "overlay")

/**
 * Lays a fragment's own graph onto this one, reading [read] and writing [output].
 *
 * A fragment that reads the composition clock runs with its frames' timestamps moved onto it, then moved back. [origin]
 * is where the frames handed to it land in the composition, and [WINDOW_CLOCK] adds where a windowed preview opened.
 *
 * A fragment with no branches of its own runs its chain and post as one branch.
 *
 * With [liveChain] set a fragment carrying [FilterFragment.live] runs that graph, each of its inputs laid onto its pad
 * by [liveChain].
 */
internal fun FilterGraphBuilder.branches(
  read: String,
  fragment: FilterFragment,
  output: String,
  origin: Duration,
  liveChain: ((input: LiveInput, origin: Duration, output: String) -> Unit)? = null,
) {
  // The composition time the window opens at, in units of the frames' time base and rounded to the nearest unit.
  val clock = "round((${formatSeconds(origin.toDouble(DurationUnit.SECONDS))}+$WINDOW_CLOCK)/TB)"
  val input =
    if (fragment.readsCompositionClock) {
      "${output}clock".also { chain(listOf(read), listOf(FilterNode("setpts", "expr" to "PTS+$clock")), it) }
    } else {
      read
    }
  val pad = { name: String -> if (name == FilterBranch.INPUT) input else "$output$name" }
  val live = fragment.live?.takeIf { liveChain != null }
  live?.inputs?.forEach { checkNotNull(liveChain)(it, origin, pad(it.pad)) }
  val laid =
    live?.branches ?: fragment.branches.ifEmpty {
      listOf(FilterBranch(listOf(FilterBranch.INPUT), fragment.chain + fragment.post, listOf(CHAIN_PAD)))
    }
  laid.forEach { branch ->
    if (branch.writes.size > 1) {
      split(pad(branch.reads.single()), branch.writes.map(pad))
    } else if (branch.reads.isEmpty()) {
      source(branch.nodes, pad(branch.writes.single()))
    } else {
      chain(branch.reads.map(pad), branch.nodes, pad(branch.writes.single()))
    }
  }
  val tail =
    if (fragment.readsCompositionClock) FilterNode("setpts", "expr" to "PTS-$clock") else FilterNode("null")
  chain(
    listOf(
      pad(
        laid
          .last()
          .writes
          .single(),
      ),
    ),
    listOf(tail),
    output,
  )
}
